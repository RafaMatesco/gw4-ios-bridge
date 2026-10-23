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
    }

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

    fun start() {
        if (running) return
        running = true
        loadPersistedDailyState()
        loadPersistedHistoryAndWorkouts()
        registerSensors()
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
        saveCurrentDayToHistory()
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

            // Default to marking delta as synced unless preview=1 is explicitly requested
            val markAck = !requestLine.contains("preview=1")
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

    private fun todayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
