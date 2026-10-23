package com.rafa.gw4bridge

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Collects Steps, Active Calories, Total Calories, Heart Rate (BPM), and Workouts
 * directly from Galaxy Watch 4 hardware sensors, persists a multi-day offline history
 * queue on internal flash storage so zero data is ever lost when away from home Wi-Fi,
 * and exposes:
 *   1. Real-time BLE GATT notifications (Heart Rate + Energy Expended kJ + Running Speed/Cadence/Distance)
 *      via BleAncsManager (100% Bluetooth, zero Wi-Fi needed).
 *   2. Local HTTP REST API on port 8765 (`GET /health` and `GET /health?ack=1`) with multi-day
 *      unsynced history & workout queue for Apple Health Shortcuts.
 */
class HealthSyncManager(
    private val context: Context,
    private val onStatsUpdated: (HealthSnapshot) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "GW4BridgeHealth"
        const val HTTP_PORT = 8765
        private const val PREFS_NAME = "gw4_health_prefs"
        private const val KEY_WORKOUTS_JSON = "persisted_workouts_json"
        private const val KEY_DAILY_HISTORY_JSON = "persisted_daily_history_json"
        private const val KEY_BODY_COMPOSITION_JSON = "persisted_body_composition_json"
    }

    data class BodyCompositionEntry(
        val id: String,
        val timestamp: Long,
        val dateIso: String,
        val weightKg: Double,
        val heightCm: Double,
        val bmi: Double,
        val bodyFatPercent: Double,
        val bodyFatMassKg: Double,
        val leanBodyMassKg: Double, // fatFreeMass
        val fatFreePercent: Double,
        val skeletalMusclePercent: Double,
        val skeletalMuscleMassKg: Double,
        val totalBodyWaterKg: Double,
        val basalMetabolicRateKcal: Int,
        var synced: Boolean = false
    )

    data class WorkoutEntry(
        val id: String,
        val type: String,
        val startTimeIso: String,
        val endTimeIso: String,
        val durationSeconds: Long,
        val activeKcal: Double,
        val avgHeartRate: Int,
        val steps: Int,
        var synced: Boolean = false
    )

    data class DailyRecord(
        val dateKey: String,
        val steps: Int,
        val activeKcal: Double,
        val basalKcal: Double,
        val totalKcal: Double,
        val distanceKm: Double,
        val avgHeartRateBpm: Int,
        var syncedSteps: Int = 0,
        var syncedActiveKcal: Double = 0.0,
        var syncedBasalKcal: Double = 0.0,
        var syncedDistanceKm: Double = 0.0,
        var syncedToIphone: Boolean = false
    )

    data class HealthSnapshot(
        val dateKey: String,
        val stepsToday: Int,
        val activeKcalToday: Double,
        val basalKcalToday: Double,
        val totalKcalToday: Double,
        val distanceKmToday: Double,
        val lastHeartRateBpm: Int,
        val activeWorkoutRunning: Boolean,
        val activeWorkoutElapsedSec: Long,
        val activeWorkoutKcal: Double,
        val pendingOfflineDaysCount: Int,
        val pendingOfflineWorkoutsCount: Int,
        val pendingOfflineBodyCompCount: Int,
        val latestBodyComposition: BodyCompositionEntry?,
        val watchIpAddress: String
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val executor = Executors.newCachedThreadPool()

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    // Daily metrics
    private var currentDayKey: String = todayKey()
    private var bootStepBaseline: Int = -1
    private var stepsToday: Int = 0
    private var lastHeartRateBpm: Int = 72
    private var heartRateSamplesCount: Int = 0
    private var heartRateSum: Long = 0

    // Active workout tracking
    private var workoutActive: Boolean = false
    private var workoutType: String = "FunctionalStrengthTraining"
    private var workoutStartMs: Long = 0L
    private var workoutStartSteps: Int = 0
    private val completedWorkouts = mutableListOf<WorkoutEntry>()
    private val dailyHistory = LinkedHashMap<String, DailyRecord>()
    private val bodyCompositionRecords = mutableListOf<BodyCompositionEntry>()

    // Last observed profile weight/height from Samsung Health logs or user defaults
    @Volatile private var lastKnownWeightKg: Double = 70.0
    @Volatile private var lastKnownHeightCm: Double = 175.0
    private var logcatProcess: Process? = null

    fun start() {
        if (running) return
        running = true
        loadPersistedDailyState()
        loadPersistedHistoryAndWorkouts()
        registerSensors()
        startSamsungHealthBiaWatcher()
        startHttpServer()
        notifyListeners()
    }

    fun stop() {
        running = false
        saveCurrentDayToHistory()
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            logcatProcess?.destroy()
            logcatProcess = null
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    fun startWorkout(type: String = "FunctionalStrengthTraining") {
        if (workoutActive) return
        workoutActive = true
        workoutType = type
        workoutStartMs = System.currentTimeMillis()
        workoutStartSteps = stepsToday
        heartRateSamplesCount = 0
        heartRateSum = 0
        try {
            sensorManager.unregisterListener(this)
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            stepSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            hrSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } catch (_: Exception) {}
        notifyListeners()
    }

    fun stopWorkout(): WorkoutEntry? {
        if (!workoutActive) return null
        workoutActive = false
        val endMs = System.currentTimeMillis()
        val durationSec = ((endMs - workoutStartMs) / 1000L).coerceAtLeast(1L)
        val avgHr = if (heartRateSamplesCount > 0) (heartRateSum / heartRateSamplesCount).toInt() else lastHeartRateBpm
        val workoutSteps = (stepsToday - workoutStartSteps).coerceAtLeast(0)
        // Accurate MET + HR calorie formula for active workout
        val minutes = durationSec / 60.0
        val hrFactor = ((avgHr - 60).coerceAtLeast(15)) * 0.085
        val kcal = ((minutes * (4.5 + hrFactor)) * 10.0).roundToInt() / 10.0

        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val entry = WorkoutEntry(
            id = "wk_${workoutStartMs}",
            type = workoutType,
            startTimeIso = isoFmt.format(Date(workoutStartMs)),
            endTimeIso = isoFmt.format(Date(endMs)),
            durationSeconds = durationSec,
            activeKcal = kcal,
            avgHeartRate = avgHr,
            steps = workoutSteps,
            synced = false
        )
        synchronized(completedWorkouts) {
            completedWorkouts.add(0, entry)
            persistWorkoutsLocked()
        }
        val prevBonus = prefs.getFloat("workout_bonus_kcal_$currentDayKey", 0f)
        prefs.edit().putFloat("workout_bonus_kcal_$currentDayKey", (prevBonus + kcal).toFloat()).apply()

        saveCurrentDayToHistory()
        registerSensors()
        notifyListeners()
        return entry
    }

    fun isWorkoutRunning(): Boolean = workoutActive

    private fun registerSensors() {
        try {
            sensorManager.unregisterListener(this)
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            stepSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            hrSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering sensors: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        val today = todayKey()
        if (today != currentDayKey) {
            // Roll over previous day into persistent offline history!
            saveCurrentDayToHistory()
            currentDayKey = today
            bootStepBaseline = -1
            stepsToday = 0
        }

        when (ev.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSinceBoot = ev.values[0].toInt()
                if (bootStepBaseline < 0) {
                    val savedBaseline = prefs.getInt("baseline_$currentDayKey", -1)
                    if (savedBaseline < 0 || totalSinceBoot < savedBaseline) {
                        bootStepBaseline = totalSinceBoot - stepsToday
                        prefs.edit().putInt("baseline_$currentDayKey", bootStepBaseline).apply()
                    } else {
                        bootStepBaseline = savedBaseline
                    }
                }
                stepsToday = (totalSinceBoot - bootStepBaseline).coerceAtLeast(0)
                prefs.edit().putInt("steps_$currentDayKey", stepsToday).apply()
                saveCurrentDayToHistory()
                notifyListeners()
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = ev.values[0].roundToInt()
                if (bpm in 35..230) {
                    lastHeartRateBpm = bpm
                    if (workoutActive) {
                        heartRateSamplesCount++
                        heartRateSum += bpm
                    }
                    prefs.edit().putInt("last_hr", lastHeartRateBpm).apply()
                    notifyListeners()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun loadPersistedDailyState() {
        currentDayKey = todayKey()
        stepsToday = prefs.getInt("steps_$currentDayKey", 0)
        bootStepBaseline = prefs.getInt("baseline_$currentDayKey", -1)
        lastHeartRateBpm = prefs.getInt("last_hr", 72)
        lastKnownWeightKg = prefs.getFloat("last_weight_kg", 70.0f).toDouble()
        lastKnownHeightCm = prefs.getFloat("last_height_cm", 175.0f).toDouble()
    }

    private fun loadPersistedHistoryAndWorkouts() {
        synchronized(completedWorkouts) {
            completedWorkouts.clear()
            val rawWk = prefs.getString(KEY_WORKOUTS_JSON, null)
            if (!rawWk.isNullOrBlank()) {
                try {
                    val arr = JSONArray(rawWk)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        completedWorkouts.add(
                            WorkoutEntry(
                                id = o.optString("id", "wk_$i"),
                                type = o.optString("type", "Running"),
                                startTimeIso = o.optString("startTime", ""),
                                endTimeIso = o.optString("endTime", ""),
                                durationSeconds = o.optLong("durationSeconds", 0L),
                                activeKcal = o.optDouble("activeEnergyKcal", 0.0),
                                avgHeartRate = o.optInt("avgHeartRateBpm", 72),
                                steps = o.optInt("steps", 0),
                                synced = o.optBoolean("synced", false)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse persisted workouts: ${e.message}")
                }
            }
        }

        synchronized(dailyHistory) {
            dailyHistory.clear()
            val rawHist = prefs.getString(KEY_DAILY_HISTORY_JSON, null)
            if (!rawHist.isNullOrBlank()) {
                try {
                    val arr = JSONArray(rawHist)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val rec = DailyRecord(
                            dateKey = o.getString("date"),
                            steps = o.optInt("steps", 0),
                            activeKcal = o.optDouble("activeEnergyKcal", 0.0),
                            basalKcal = o.optDouble("basalEnergyKcal", 0.0),
                            totalKcal = o.optDouble("totalEnergyKcal", 0.0),
                            distanceKm = o.optDouble("distanceKm", 0.0),
                            avgHeartRateBpm = o.optInt("heartRateBpm", 72),
                            syncedSteps = o.optInt("syncedSteps", 0),
                            syncedActiveKcal = o.optDouble("syncedActiveKcal", 0.0),
                            syncedBasalKcal = o.optDouble("syncedBasalKcal", 0.0),
                            syncedDistanceKm = o.optDouble("syncedDistanceKm", 0.0),
                            syncedToIphone = o.optBoolean("synced", false)
                        )
                        dailyHistory[rec.dateKey] = rec
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse daily history: ${e.message}")
                }
            }
        }

        synchronized(bodyCompositionRecords) {
            bodyCompositionRecords.clear()
            val rawBia = prefs.getString(KEY_BODY_COMPOSITION_JSON, null)
            if (!rawBia.isNullOrBlank()) {
                try {
                    val arr = JSONArray(rawBia)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        bodyCompositionRecords.add(
                            BodyCompositionEntry(
                                id = o.optString("id", "bia_$i"),
                                timestamp = o.optLong("timestamp", 0L),
                                dateIso = o.optString("dateIso", ""),
                                weightKg = o.optDouble("weightKg", 70.0),
                                heightCm = o.optDouble("heightCm", 175.0),
                                bmi = o.optDouble("bmi", 22.9),
                                bodyFatPercent = o.optDouble("bodyFatPercent", 0.0),
                                bodyFatMassKg = o.optDouble("bodyFatMassKg", 0.0),
                                leanBodyMassKg = o.optDouble("leanBodyMassKg", 0.0),
                                fatFreePercent = o.optDouble("fatFreePercent", 0.0),
                                skeletalMusclePercent = o.optDouble("skeletalMusclePercent", 0.0),
                                skeletalMuscleMassKg = o.optDouble("skeletalMuscleMassKg", 0.0),
                                totalBodyWaterKg = o.optDouble("totalBodyWaterKg", 0.0),
                                basalMetabolicRateKcal = o.optInt("basalMetabolicRateKcal", 0),
                                synced = o.optBoolean("synced", false)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse body composition records: ${e.message}")
                }
            }
        }
        saveCurrentDayToHistory()
    }

    private fun persistBodyCompositionLocked() {
        val arr = JSONArray()
        for (b in bodyCompositionRecords.take(90)) {
            arr.put(bodyCompToJson(b))
        }
        prefs.edit().putString(KEY_BODY_COMPOSITION_JSON, arr.toString()).apply()
    }

    private fun bodyCompToJson(b: BodyCompositionEntry): JSONObject {
        return JSONObject().apply {
            put("id", b.id)
            put("timestamp", b.timestamp)
            put("dateIso", b.dateIso)
            put("weightKg", b.weightKg)
            put("heightCm", b.heightCm)
            put("bmi", b.bmi)
            put("bodyFatPercent", b.bodyFatPercent)
            put("bodyFatRatio", ((b.bodyFatPercent / 100.0) * 10000.0).roundToInt() / 10000.0)
            put("bodyFatMassKg", b.bodyFatMassKg)
            put("leanBodyMassKg", b.leanBodyMassKg)
            put("fatFreeMassKg", b.leanBodyMassKg)
            put("fatFreePercent", b.fatFreePercent)
            put("skeletalMusclePercent", b.skeletalMusclePercent)
            put("skeletalMuscleMassKg", b.skeletalMuscleMassKg)
            put("totalBodyWaterKg", b.totalBodyWaterKg)
            put("basalMetabolicRateKcal", b.basalMetabolicRateKcal)
            put("synced", b.synced)
        }
    }

    /**
     * Records a new Body Composition (BIA) measurement (either captured automatically from
     * Samsung Health's BIA sensor logs or entered via the watch UI) and queues it for Apple Health sync.
     */
    fun recordBodyComposition(
        timestampMs: Long = System.currentTimeMillis(),
        weightKg: Double = lastKnownWeightKg,
        heightCm: Double = lastKnownHeightCm,
        bodyFatPercent: Double,
        bodyFatMassKg: Double,
        leanBodyMassKg: Double,
        fatFreePercent: Double,
        skeletalMusclePercent: Double,
        skeletalMuscleMassKg: Double,
        totalBodyWaterKg: Double,
        basalMetabolicRateKcal: Int
    ): BodyCompositionEntry {
        val w = if (weightKg > 20.0) weightKg else lastKnownWeightKg
        val h = if (heightCm > 80.0) heightCm else lastKnownHeightCm
        lastKnownWeightKg = w
        lastKnownHeightCm = h
        prefs.edit()
            .putFloat("last_weight_kg", w.toFloat())
            .putFloat("last_height_cm", h.toFloat())
            .apply()

        val heightM = h / 100.0
        val bmiVal = if (heightM > 0.5) ((w / (heightM * heightM)) * 10.0).roundToInt() / 10.0 else 0.0
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val entry = BodyCompositionEntry(
            id = "bia_$timestampMs",
            timestamp = timestampMs,
            dateIso = isoFmt.format(Date(timestampMs)),
            weightKg = (w * 10.0).roundToInt() / 10.0,
            heightCm = (h * 10.0).roundToInt() / 10.0,
            bmi = bmiVal,
            bodyFatPercent = (bodyFatPercent * 10.0).roundToInt() / 10.0,
            bodyFatMassKg = (bodyFatMassKg * 10.0).roundToInt() / 10.0,
            leanBodyMassKg = (leanBodyMassKg * 10.0).roundToInt() / 10.0,
            fatFreePercent = (fatFreePercent * 10.0).roundToInt() / 10.0,
            skeletalMusclePercent = (skeletalMusclePercent * 10.0).roundToInt() / 10.0,
            skeletalMuscleMassKg = (skeletalMuscleMassKg * 10.0).roundToInt() / 10.0,
            totalBodyWaterKg = (totalBodyWaterKg * 10.0).roundToInt() / 10.0,
            basalMetabolicRateKcal = basalMetabolicRateKcal,
            synced = false
        )

        synchronized(bodyCompositionRecords) {
            // Avoid duplicate insertion if the exact same measurement timestamp was already logged
            val existingIdx = bodyCompositionRecords.indexOfFirst {
                kotlin.math.abs(it.timestamp - timestampMs) < 15_000L
            }
            if (existingIdx >= 0) {
                bodyCompositionRecords[existingIdx] = entry
            } else {
                bodyCompositionRecords.add(0, entry)
            }
            persistBodyCompositionLocked()
        }
        Log.i(TAG, "Recorded new Body Composition (BIA): weight=${entry.weightKg}kg, fat=${entry.bodyFatPercent}%, muscle=${entry.skeletalMuscleMassKg}kg")
        notifyListeners()
        return entry
    }

    /**
     * Watches Samsung Health (`com.samsung.android.wear.shealth`) BIA logs in real time (`READ_LOGS`).
     * Whenever the user finishes a Body Composition measurement on the watch, Samsung Health logs:
     *   - `[biaTrackerObserver]: <weight>` or `[insertData]: <weight>`
     *   - `BiaSensorData(timeInMillis=..., measuringStatus=..., fatFree=..., fatFreeMass=..., bodyFat=..., bodyFatMass=..., totalBodyWater=..., basalMetabolicRate=..., skeletalMuscle=..., skeletalMuscleMass=..., progress=1.0)`
     * This watcher intercepts the completed measurement automatically and queues it for iPhone sync!
     */
    private fun startSamsungHealthBiaWatcher() {
        executor.execute {
            val biaRegex = Regex(
                """BiaSensorData\(timeInMillis=(\d+),\s*measuringStatus=[^,]+,\s*fatFree=([\d.]+),\s*fatFreeMass=([\d.]+),\s*bodyFat=([\d.]+),\s*bodyFatMass=([\d.]+),\s*totalBodyWater=([\d.]+),\s*basalMetabolicRate=([\d.]+),\s*skeletalMuscle=([\d.]+),\s*skeletalMuscleMass=([\d.]+),\s*progress=([\d.]+)\)"""
            )
            val weightRegex = Regex("""(?:\[biaTrackerObserver\]|\[insertData\]|weightInKg)\s*:\s*([\d.]+)""")
            val heightRegex = Regex("""height(?:InCm)?\s*[:=]\s*([\d.]+)""", RegexOption.IGNORE_CASE)

            while (running) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief"))
                    logcatProcess = proc
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (running) {
                        val l = reader.readLine() ?: break
                        if (!l.contains("BodyComposition") && !l.contains("BiaSensorData") && !l.contains("BiaTracker")) {
                            continue
                        }

                        weightRegex.find(l)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { w ->
                            if (w in 25.0..250.0) {
                                lastKnownWeightKg = w
                                prefs.edit().putFloat("last_weight_kg", w.toFloat()).apply()
                            }
                        }
                        heightRegex.find(l)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { h ->
                            if (h in 100.0..240.0) {
                                lastKnownHeightCm = h
                                prefs.edit().putFloat("last_height_cm", h.toFloat()).apply()
                            }
                        }

                        val match = biaRegex.find(l)
                        if (match != null) {
                            val timeMs = match.groupValues[1].toLongOrNull() ?: System.currentTimeMillis()
                            val fatFreePct = match.groupValues[2].toDoubleOrNull() ?: 0.0
                            val fatFreeMass = match.groupValues[3].toDoubleOrNull() ?: 0.0
                            val bodyFatPct = match.groupValues[4].toDoubleOrNull() ?: 0.0
                            val bodyFatMass = match.groupValues[5].toDoubleOrNull() ?: 0.0
                            val bodyWater = match.groupValues[6].toDoubleOrNull() ?: 0.0
                            val bmr = match.groupValues[7].toDoubleOrNull()?.roundToInt() ?: 0
                            val skelMusclePct = match.groupValues[8].toDoubleOrNull() ?: 0.0
                            val skelMuscleMass = match.groupValues[9].toDoubleOrNull() ?: 0.0
                            val progress = match.groupValues[10].toDoubleOrNull() ?: 0.0

                            if (progress >= 0.99 && (bodyFatPct > 0.0 || skelMuscleMass > 0.0)) {
                                // Derive weight from fatFreeMass + bodyFatMass if plausible
                                val derivedWeight = fatFreeMass + bodyFatMass
                                val finalWeight = if (derivedWeight in 30.0..250.0) derivedWeight else lastKnownWeightKg
                                recordBodyComposition(
                                    timestampMs = if (timeMs > 1_600_000_000_000L) timeMs else System.currentTimeMillis(),
                                    weightKg = finalWeight,
                                    heightCm = lastKnownHeightCm,
                                    bodyFatPercent = bodyFatPct,
                                    bodyFatMassKg = bodyFatMass,
                                    leanBodyMassKg = fatFreeMass,
                                    fatFreePercent = fatFreePct,
                                    skeletalMusclePercent = skelMusclePct,
                                    skeletalMuscleMassKg = skelMuscleMass,
                                    totalBodyWaterKg = bodyWater,
                                    basalMetabolicRateKcal = bmr
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "BIA logcat watcher error: ${e.message}")
                }
                try {
                    Thread.sleep(5000L)
                } catch (_: Exception) {}
            }
        }
    }

    private fun persistWorkoutsLocked() {
        val arr = JSONArray()
        for (w in completedWorkouts.take(60)) {
            arr.put(
                JSONObject().apply {
                    put("id", w.id)
                    put("type", w.type)
                    put("startTime", w.startTimeIso)
                    put("endTime", w.endTimeIso)
                    put("durationSeconds", w.durationSeconds)
                    put("activeEnergyKcal", w.activeKcal)
                    put("avgHeartRateBpm", w.avgHeartRate)
                    put("steps", w.steps)
                    put("synced", w.synced)
                }
            )
        }
        prefs.edit().putString(KEY_WORKOUTS_JSON, arr.toString()).apply()
    }

    private fun saveCurrentDayToHistory() {
        val snap = computeTodayMetrics()
        synchronized(dailyHistory) {
            val prev = dailyHistory[currentDayKey]
            val prevSyncedSteps = prev?.syncedSteps ?: prefs.getInt("synced_steps_$currentDayKey", 0)
            val prevSyncedActive = prev?.syncedActiveKcal ?: prefs.getFloat("synced_active_$currentDayKey", 0f).toDouble()
            val prevSyncedBasal = prev?.syncedBasalKcal ?: prefs.getFloat("synced_basal_$currentDayKey", 0f).toDouble()
            val prevSyncedDist = prev?.syncedDistanceKm ?: prefs.getFloat("synced_dist_$currentDayKey", 0f).toDouble()

            dailyHistory[currentDayKey] = DailyRecord(
                dateKey = currentDayKey,
                steps = snap.first,
                activeKcal = snap.second,
                basalKcal = snap.third,
                totalKcal = ((snap.second + snap.third) * 10.0).roundToInt() / 10.0,
                distanceKm = ((snap.first * 0.00076) * 100.0).roundToInt() / 100.0,
                avgHeartRateBpm = lastHeartRateBpm,
                syncedSteps = prevSyncedSteps,
                syncedActiveKcal = prevSyncedActive,
                syncedBasalKcal = prevSyncedBasal,
                syncedDistanceKm = prevSyncedDist,
                syncedToIphone = false
            )
            persistDailyHistoryLocked()
        }
    }

    private fun persistDailyHistoryLocked() {
        val arr = JSONArray()
        for (rec in dailyHistory.values.toList().takeLast(30)) {
            arr.put(
                JSONObject().apply {
                    put("date", rec.dateKey)
                    put("steps", rec.steps)
                    put("activeEnergyKcal", rec.activeKcal)
                    put("basalEnergyKcal", rec.basalKcal)
                    put("totalEnergyKcal", rec.totalKcal)
                    put("distanceKm", rec.distanceKm)
                    put("heartRateBpm", rec.avgHeartRateBpm)
                    put("syncedSteps", rec.syncedSteps)
                    put("syncedActiveKcal", rec.syncedActiveKcal)
                    put("syncedBasalKcal", rec.syncedBasalKcal)
                    put("syncedDistanceKm", rec.syncedDistanceKm)
                    put("synced", rec.syncedToIphone)
                }
            )
        }
        prefs.edit().putString(KEY_DAILY_HISTORY_JSON, arr.toString()).apply()
    }

    private fun computeTodayMetrics(): Triple<Int, Double, Double> {
        val stepKcal = stepsToday * 0.045
        val workoutBonus = prefs.getFloat("workout_bonus_kcal_$currentDayKey", 0f).toDouble()
        val activeElapsedSec = if (workoutActive) ((System.currentTimeMillis() - workoutStartMs) / 1000L).coerceAtLeast(0L) else 0L
        val activeWorkoutKcal = if (workoutActive) {
            val avgHr = if (heartRateSamplesCount > 0) (heartRateSum / heartRateSamplesCount).toInt() else lastHeartRateBpm
            val mins = activeElapsedSec / 60.0
            ((mins * (4.5 + ((avgHr - 60).coerceAtLeast(15) * 0.085))) * 10.0).roundToInt() / 10.0
        } else 0.0

        val activeKcal = ((stepKcal + workoutBonus + activeWorkoutKcal) * 10.0).roundToInt() / 10.0
        val cal = java.util.Calendar.getInstance()
        val minutesOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val basalKcal = (((minutesOfDay / 1440.0) * 1750.0) * 10.0).roundToInt() / 10.0
        return Triple(stepsToday, activeKcal, basalKcal)
    }

    fun getCurrentSnapshot(): HealthSnapshot {
        val (steps, activeKcal, basalKcal) = computeTodayMetrics()
        val activeElapsedSec = if (workoutActive) ((System.currentTimeMillis() - workoutStartMs) / 1000L).coerceAtLeast(0L) else 0L
        val activeWorkoutKcal = if (workoutActive) {
            val avgHr = if (heartRateSamplesCount > 0) (heartRateSum / heartRateSamplesCount).toInt() else lastHeartRateBpm
            val mins = activeElapsedSec / 60.0
            ((mins * (4.5 + ((avgHr - 60).coerceAtLeast(15) * 0.085))) * 10.0).roundToInt() / 10.0
        } else 0.0
        val totalKcal = ((activeKcal + basalKcal) * 10.0).roundToInt() / 10.0
        val distanceKm = ((steps * 0.00076) * 100.0).roundToInt() / 100.0

        val pendingDays = synchronized(dailyHistory) {
            dailyHistory.values.count { !it.syncedToIphone }
        }
        val pendingWks = synchronized(completedWorkouts) {
            completedWorkouts.count { !it.synced }
        }
        val (pendingBia, latestBia) = synchronized(bodyCompositionRecords) {
            Pair(bodyCompositionRecords.count { !it.synced }, bodyCompositionRecords.firstOrNull())
        }

        return HealthSnapshot(
            dateKey = currentDayKey,
            stepsToday = steps,
            activeKcalToday = activeKcal,
            basalKcalToday = basalKcal,
            totalKcalToday = totalKcal,
            distanceKmToday = distanceKm,
            lastHeartRateBpm = lastHeartRateBpm,
            activeWorkoutRunning = workoutActive,
            activeWorkoutElapsedSec = activeElapsedSec,
            activeWorkoutKcal = activeWorkoutKcal,
            pendingOfflineDaysCount = pendingDays,
            pendingOfflineWorkoutsCount = pendingWks,
            pendingOfflineBodyCompCount = pendingBia,
            latestBodyComposition = latestBia,
            watchIpAddress = getWatchWifiIp()
        )
    }

    private fun notifyListeners() {
        onStatsUpdated(getCurrentSnapshot())
    }

    fun getWatchWifiIp(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) Formatter.formatIpAddress(ip) else "offline-ble"
        } catch (_: Exception) {
            "offline-ble"
        }
    }

    private fun buildHealthJson(markSynced: Boolean): JSONObject {
        val snap = getCurrentSnapshot()
        val workoutsArray = JSONArray()
        val unsyncedWorkoutsArray = JSONArray()

        synchronized(completedWorkouts) {
            for (w in completedWorkouts) {
                val obj = JSONObject().apply {
                    put("id", w.id)
                    put("type", w.type)
                    put("startTime", w.startTimeIso)
                    put("endTime", w.endTimeIso)
                    put("durationSeconds", w.durationSeconds)
                    put("activeEnergyKcal", w.activeKcal)
                    put("avgHeartRateBpm", w.avgHeartRate)
                    put("steps", w.steps)
                    put("synced", w.synced)
                }
                workoutsArray.put(obj)
                if (!w.synced) {
                    unsyncedWorkoutsArray.put(obj)
                    if (markSynced) {
                        w.synced = true
                    }
                }
            }
            if (markSynced) {
                persistWorkoutsLocked()
            }
        }

        var todayDeltaSteps = 0
        var todayDeltaActiveKcal = 0.0
        var todayDeltaBasalKcal = 0.0
        var todayDeltaDistanceKm = 0.0

        val pendingDaysArray = JSONArray()
        synchronized(dailyHistory) {
            for (rec in dailyHistory.values) {
                val dSteps = (rec.steps - rec.syncedSteps).coerceAtLeast(0)
                val dActive = (((rec.activeKcal - rec.syncedActiveKcal).coerceAtLeast(0.0)) * 10.0).roundToInt() / 10.0
                val dBasal = (((rec.basalKcal - rec.syncedBasalKcal).coerceAtLeast(0.0)) * 10.0).roundToInt() / 10.0
                val dDist = (((rec.distanceKm - rec.syncedDistanceKm).coerceAtLeast(0.0)) * 100.0).roundToInt() / 100.0
                val dTotal = ((dActive + dBasal) * 10.0).roundToInt() / 10.0

                if (rec.dateKey == currentDayKey) {
                    todayDeltaSteps = dSteps
                    todayDeltaActiveKcal = dActive
                    todayDeltaBasalKcal = dBasal
                    todayDeltaDistanceKm = dDist
                }

                if (!rec.syncedToIphone || rec.dateKey == currentDayKey) {
                    pendingDaysArray.put(
                        JSONObject().apply {
                            put("date", rec.dateKey)
                            put("steps", dSteps)
                            put("activeEnergyKcal", dActive)
                            put("basalEnergyKcal", dBasal)
                            put("totalEnergyKcal", dTotal)
                            put("distanceKm", dDist)
                            put("totalStepsDay", rec.steps)
                            put("totalActiveEnergyKcalDay", rec.activeKcal)
                            put("heartRateBpm", rec.avgHeartRateBpm)
                        }
                    )
                    if (markSynced) {
                        rec.syncedSteps = rec.steps
                        rec.syncedActiveKcal = rec.activeKcal
                        rec.syncedBasalKcal = rec.basalKcal
                        rec.syncedDistanceKm = rec.distanceKm
                        if (rec.dateKey == currentDayKey) {
                            prefs.edit()
                                .putInt("synced_steps_$currentDayKey", rec.syncedSteps)
                                .putFloat("synced_active_$currentDayKey", rec.syncedActiveKcal.toFloat())
                                .putFloat("synced_basal_$currentDayKey", rec.syncedBasalKcal.toFloat())
                                .putFloat("synced_dist_$currentDayKey", rec.syncedDistanceKm.toFloat())
                                .apply()
                        } else {
                            rec.syncedToIphone = true
                        }
                    }
                }
            }
            if (markSynced) {
                persistDailyHistoryLocked()
            }
        }

        val todayDeltaTotalKcal = ((todayDeltaActiveKcal + todayDeltaBasalKcal) * 10.0).roundToInt() / 10.0

        val bodyCompArray = JSONArray()
        val unsyncedBodyCompArray = JSONArray()
        var latestBodyCompJson: JSONObject? = null
        var unsyncedLatestBodyComp: BodyCompositionEntry? = null

        synchronized(bodyCompositionRecords) {
            latestBodyCompJson = bodyCompositionRecords.firstOrNull()?.let { bodyCompToJson(it) }
            unsyncedLatestBodyComp = bodyCompositionRecords.firstOrNull { !it.synced }
            for (b in bodyCompositionRecords) {
                val obj = bodyCompToJson(b)
                bodyCompArray.put(obj)
                if (!b.synced) {
                    unsyncedBodyCompArray.put(obj)
                    if (markSynced) {
                        b.synced = true
                    }
                }
            }
            if (markSynced) {
                persistBodyCompositionLocked()
            }
        }

        return JSONObject().apply {
            put("device", "Samsung Galaxy Watch 4 (SM-R870)")
            put("date", snap.dateKey)
            put("timestamp", System.currentTimeMillis())
            // Incremental DELTA values for Apple Health "Log Health Sample" (never duplicates!)
            put("steps", todayDeltaSteps)
            put("activeEnergyKcal", todayDeltaActiveKcal)
            put("basalEnergyKcal", todayDeltaBasalKcal)
            put("totalEnergyKcal", todayDeltaTotalKcal)
            put("distanceKm", todayDeltaDistanceKm)
            put("heartRateBpm", snap.lastHeartRateBpm)
            // Cumulative daily totals for reference/display
            put("totalStepsToday", snap.stepsToday)
            put("totalActiveEnergyKcalToday", snap.activeKcalToday)
            put("totalBasalEnergyKcalToday", snap.basalKcalToday)
            put("totalDistanceKmToday", snap.distanceKmToday)
            // Body Composition (BIA) Incremental Delta / Unsynced top-level fields (0 when already synced!)
            put("hasNewBodyComposition", unsyncedLatestBodyComp != null)
            put("weightKg", unsyncedLatestBodyComp?.weightKg ?: 0.0)
            put("heightCm", unsyncedLatestBodyComp?.heightCm ?: 0.0)
            put("bmi", unsyncedLatestBodyComp?.bmi ?: 0.0)
            put("bodyFatPercent", unsyncedLatestBodyComp?.bodyFatPercent ?: 0.0)
            put("bodyFatRatio", unsyncedLatestBodyComp?.let { ((it.bodyFatPercent / 100.0) * 10000.0).roundToInt() / 10000.0 } ?: 0.0)
            put("bodyFatMassKg", unsyncedLatestBodyComp?.bodyFatMassKg ?: 0.0)
            put("leanBodyMassKg", unsyncedLatestBodyComp?.leanBodyMassKg ?: 0.0)
            put("fatFreeMassKg", unsyncedLatestBodyComp?.leanBodyMassKg ?: 0.0)
            put("fatFreePercent", unsyncedLatestBodyComp?.fatFreePercent ?: 0.0)
            put("skeletalMusclePercent", unsyncedLatestBodyComp?.skeletalMusclePercent ?: 0.0)
            put("skeletalMuscleMassKg", unsyncedLatestBodyComp?.skeletalMuscleMassKg ?: 0.0)
            put("totalBodyWaterKg", unsyncedLatestBodyComp?.totalBodyWaterKg ?: 0.0)
            put("basalMetabolicRateKcal", unsyncedLatestBodyComp?.basalMetabolicRateKcal ?: 0)
            put("latestBodyComposition", latestBodyCompJson ?: JSONObject.NULL)
            put("unsyncedBodyCompositions", unsyncedBodyCompArray)
            put("bodyCompositions", bodyCompArray)
            put("pendingDays", pendingDaysArray)
            put("unsyncedWorkouts", unsyncedWorkoutsArray)
            put("workouts", workoutsArray)
        }
    }

    private fun startHttpServer() {
        executor.execute {
            try {
                serverSocket = ServerSocket(HTTP_PORT)
                Log.d(TAG, "Apple Health HTTP Bridge listening on port $HTTP_PORT")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleHttpClient(client) }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "HTTP server stopped: ${e.message}")
                }
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            Log.d(TAG, "HTTP request from iPhone: $requestLine")

            if (requestLine.contains("/bia")) {
                parseAndRecordBiaQuery(requestLine)
            }

            // Default to marking delta as synced unless preview=1 is explicitly requested
            val markAck = !requestLine.contains("preview=1") && !requestLine.contains("/bia")
            val jsonBody = buildHealthJson(markAck).toString(2)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }

            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
            if (markAck) {
                notifyListeners()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling HTTP client: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseAndRecordBiaQuery(requestLine: String) {
        try {
            val pathPart = requestLine.split(" ").getOrNull(1) ?: return
            val qIdx = pathPart.indexOf('?')
            if (qIdx < 0) return
            val query = pathPart.substring(qIdx + 1)
            val params = mutableMapOf<String, Double>()
            for (pair in query.split("&")) {
                val kv = pair.split("=")
                if (kv.size == 2) {
                    kv[1].toDoubleOrNull()?.let { params[kv[0]] = it }
                }
            }
            val w = params["weight"] ?: lastKnownWeightKg
            val h = params["height"] ?: lastKnownHeightCm
            val fatPct = params["fatPct"] ?: 0.0
            val fatMass = params["fatMass"] ?: ((w * fatPct / 100.0) * 10.0).roundToInt() / 10.0
            val leanMass = params["leanMass"] ?: ((w - fatMass).coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0
            val fatFreePct = params["fatFreePct"] ?: (100.0 - fatPct).coerceAtLeast(0.0)
            val muscleMass = params["muscleMass"] ?: 0.0
            val musclePct = params["musclePct"] ?: if (w > 0 && muscleMass > 0) ((muscleMass / w) * 1000.0).roundToInt() / 10.0 else 0.0
            val waterKg = params["waterKg"] ?: ((leanMass * 0.73) * 10.0).roundToInt() / 10.0
            val bmr = params["bmr"]?.roundToInt() ?: (370 + (21.6 * leanMass)).roundToInt()

            recordBodyComposition(
                timestampMs = System.currentTimeMillis(),
                weightKg = w,
                heightCm = h,
                bodyFatPercent = fatPct,
                bodyFatMassKg = fatMass,
                leanBodyMassKg = leanMass,
                fatFreePercent = fatFreePct,
                skeletalMusclePercent = musclePct,
                skeletalMuscleMassKg = muscleMass,
                totalBodyWaterKg = waterKg,
                basalMetabolicRateKcal = bmr
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse /bia query: ${e.message}")
        }
    }

    private fun todayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
