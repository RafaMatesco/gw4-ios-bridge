package com.rafa.gw4bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Round-screen friendly Wear OS dashboard for Galaxy Watch 4:
 * - Shows live iPhone BLE ANCS connection state + Reconnect button
 * - Shows live Steps, Active/Total Calories, Distance, and Heart Rate (BPM)
 * - Allows starting/stopping a Workout session with live calorie & BPM tracking
 * - Displays the local HTTP endpoint (`http://<watch-ip>:8765/health`) for 1-tap Apple Health Shortcut sync
 */
class MainActivity : Activity() {

    private var bridgeService: BridgeService? = null
    private var isBound = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var statusBadge: TextView
    private lateinit var metricsText: TextView
    private lateinit var workoutStatusText: TextView
    private lateinit var lastNotifText: TextView
    private lateinit var endpointText: TextView
    private lateinit var btnWorkout: Button
    private lateinit var btnReconnect: Button

    private val uiUpdateCallback: () -> Unit = {
        uiHandler.post { refreshUi() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BridgeService.LocalBinder ?: return
            bridgeService = binder.getService()
            isBound = true
            bridgeService?.registerUiCallback(uiUpdateCallback)
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService?.unregisterUiCallback(uiUpdateCallback)
            bridgeService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWearUi()
        requestRequiredPermissions()
        startAndBindBridgeService()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        if (isBound) {
            bridgeService?.unregisterUiCallback(uiUpdateCallback)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun setupWearUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 42, 36, 64)
        }

        val header = TextView(this).apply {
            text = "GW4 ↔ iPhone 15 Pro"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
            gravity = Gravity.CENTER
        }
        container.addView(header)

        statusBadge = TextView(this).apply {
            text = "Iniciando ponte BLE..."
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 12)
            background = roundedBg("#1F2937", 24f)
        }
        val badgeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
        container.addView(statusBadge, badgeParams)

        metricsText = TextView(this).apply {
            text = "Passos: 0 | ❤️ -- BPM\n🔥 0.0 kcal ativas (0.0 total)"
            textSize = 12f
            setTextColor(Color.parseColor("#E6EDF3"))
            gravity = Gravity.CENTER
            setPadding(18, 14, 18, 14)
            background = roundedBg("#161B22", 20f)
        }
        val metricsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10 }
        container.addView(metricsText, metricsParams)

        workoutStatusText = TextView(this).apply {
            text = "Nenhum treino ativo"
            textSize = 11f
            setTextColor(Color.parseColor("#3FB950"))
            gravity = Gravity.CENTER
            setPadding(10, 6, 10, 6)
        }
        container.addView(workoutStatusText)

        btnWorkout = Button(this).apply {
            text = "▶ Iniciar Treino"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#238636", 30f)
            setOnClickListener {
                val svc = bridgeService ?: return@setOnClickListener
                if (svc.healthManager.isWorkoutRunning()) {
                    val entry = svc.healthManager.stopWorkout()
                    Toast.makeText(
                        this@MainActivity,
                        "Treino salvo: ${entry?.activeKcal ?: 0.0} kcal",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    svc.healthManager.startWorkout("FunctionalStrengthTraining")
                    Toast.makeText(
                        this@MainActivity,
                        "Treino iniciado! Monitorando BPM e kcal",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshUi()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
        container.addView(btnWorkout, btnParams)

        btnReconnect = Button(this).apply {
            text = "🔄 Reconectar iPhone (ANCS)"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedBg("#1F6FEB", 30f)
            setOnClickListener {
                bridgeService?.bleManager?.connectToBondedIphone()
                Toast.makeText(this@MainActivity, "Reconectando GATT ANCS...", Toast.LENGTH_SHORT).show()
            }
        }
        val reconnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
        container.addView(btnReconnect, reconnParams)

        lastNotifText = TextView(this).apply {
            text = "Última notif: nenhuma"
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 6)
        }
        container.addView(lastNotifText)

        endpointText = TextView(this).apply {
            text = "Apple Health Sync:\nhttp://192.168.15.72:8765/health"
            textSize = 10f
            setTextColor(Color.parseColor("#79C0FF"))
            gravity = Gravity.CENTER
            setPadding(10, 6, 10, 16)
        }
        container.addView(endpointText)

        scroll.addView(container)
        setContentView(scroll)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshUi() {
        val svc = bridgeService ?: return
        statusBadge.text = svc.connectionStatus
        statusBadge.background = roundedBg(
            if (svc.isIphoneConnected) "#1B4332" else "#3D1E1E",
            24f
        )

        val snap = svc.healthManager.getCurrentSnapshot()
        val biaLine = snap.latestBodyComposition?.let {
            "\n⚖️ BIA: ${it.weightKg}kg | ${it.bodyFatPercent}% gord | ${it.skeletalMuscleMassKg}kg músc"
        } ?: "\n⚖️ BIA: Aguardando medição Samsung Health"

        metricsText.text = "👟 ${snap.stepsToday} passos (${snap.distanceKmToday} km)\n" +
            "❤️ ${snap.lastHeartRateBpm} BPM (BLE Live)\n" +
            "🔥 ${snap.activeKcalToday} kcal ativas\n" +
            "⚡ ${snap.totalKcalToday} kcal totais hoje" +
            biaLine + "\n" +
            "💾 Cofre: ${snap.pendingOfflineDaysCount}d | ${snap.pendingOfflineWorkoutsCount} tr | ${snap.pendingOfflineBodyCompCount} BIA"

        if (snap.activeWorkoutRunning) {
            val mins = snap.activeWorkoutElapsedSec / 60
            val secs = snap.activeWorkoutElapsedSec % 60
            workoutStatusText.text = "⏱ Treino: %02d:%02d • %.1f kcal".format(mins, secs, snap.activeWorkoutKcal)
            btnWorkout.text = "⏹ Finalizar e Salvar Treino"
            btnWorkout.background = roundedBg("#DA3633", 30f)
        } else {
            workoutStatusText.text = "BLE 0x180D + Cofre Offline Ativos"
            btnWorkout.text = "▶ Iniciar Treino"
            btnWorkout.background = roundedBg("#238636", 30f)
        }

        lastNotifText.text = svc.lastNotificationSummary
        endpointText.text = "BLE Direto + Cofre Offline:\nhttp://${snap.watchIpAddress}:${HealthSyncManager.HTTP_PORT}/health?ack=1"
    }

    private fun roundedBg(hexColor: String, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp
            setColor(Color.parseColor(hexColor))
        }
    }

    private fun startAndBindBridgeService() {
        val svcIntent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }
}
