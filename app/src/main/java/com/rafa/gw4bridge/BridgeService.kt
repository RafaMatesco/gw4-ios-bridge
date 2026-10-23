package com.rafa.gw4bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Persistent foreground service on Galaxy Watch 4 that orchestrates:
 * 1. BleAncsManager (ANCS notifications, incoming calls, positive/negative actions)
 * 2. HealthSyncManager (Steps, Heart Rate, Calories, Workouts, and local HTTP REST API for Apple Health)
 */
class BridgeService : Service(), BleAncsManager.Listener {

    companion object {
        private const val TAG = "GW4BridgeService"
        const val CHANNEL_FOREGROUND = "gw4_bridge_status_channel"
        const val CHANNEL_NOTIFICATIONS = "gw4_iphone_notifications_channel"
        const val CHANNEL_CALLS = "gw4_iphone_calls_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1001

        const val ACTION_PERFORM_ANCS = "com.rafa.gw4bridge.PERFORM_ANCS"
        const val EXTRA_UID = "extra_uid"
        const val EXTRA_POSITIVE = "extra_positive"
        const val EXTRA_DISMISS_ONLY = "extra_dismiss_only"
        const val EXTRA_NOTIF_ID = "extra_notif_id"

        var instance: BridgeService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManager
    lateinit var bleManager: BleAncsManager
        private set
    lateinit var healthManager: HealthSyncManager
        private set

    var connectionStatus: String = "Iniciando serviço..."
        private set
    var isIphoneConnected: Boolean = false
        private set
    var connectedIphoneName: String? = null
        private set
    var lastNotificationSummary: String = "Nenhuma notificação recebida ainda"
        private set

    private val uiCallbacks = mutableSetOf<() -> Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundStatusNotification("Aguardando conexão com iPhone 15 Pro...")
        )

        bleManager = BleAncsManager(this, this)
        healthManager = HealthSyncManager(this) { snap ->
            bleManager.broadcastHealthOverBle(
                heartRateBpm = snap.lastHeartRateBpm,
                activeKcal = snap.activeKcalToday,
                stepsToday = snap.stepsToday,
                distanceKm = snap.distanceKmToday,
                isWorkout = snap.activeWorkoutRunning
            )
            notifyUi()
        }

        bleManager.start()
        healthManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PERFORM_ANCS) {
            val uid = intent.getIntExtra(EXTRA_UID, -1)
            val positive = intent.getBooleanExtra(EXTRA_POSITIVE, false)
            val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
            if (notifId != -1) {
                notificationManager.cancel(notifId)
            }
            if (uid != -1) {
                Log.d(TAG, "Performing ANCS action on uid=$uid positive=$positive")
                bleManager.performNotificationAction(uid, positive)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instance = null
        bleManager.stop()
        healthManager.stop()
        super.onDestroy()
    }

    fun registerUiCallback(cb: () -> Unit) {
        uiCallbacks.add(cb)
        cb()
    }

    fun unregisterUiCallback(cb: () -> Unit) {
        uiCallbacks.remove(cb)
    }

    private fun notifyUi() {
        for (cb in uiCallbacks.toList()) {
            cb()
        }
    }

    override fun onConnectionStateChanged(statusText: String, connected: Boolean, deviceName: String?) {
        connectionStatus = statusText
        isIphoneConnected = connected
        connectedIphoneName = deviceName
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundStatusNotification(statusText)
        )
        notifyUi()
    }

    override fun onNotificationReceived(deviceAddress: String, notification: AncsProtocol.ParsedNotification) {
        val appName = notification.friendlyAppName()
        val displayTitle = if (notification.title.isNotBlank()) {
            "$appName • ${notification.title}"
        } else {
            appName
        }
        val displayBody = buildString {
            if (notification.subtitle.isNotBlank()) {
                append(notification.subtitle).append("\n")
            }
            append(notification.message)
        }.ifBlank { "Nova notificação no iPhone" }

        lastNotificationSummary = "[$appName] ${notification.title}: ${notification.message}"
        notifyUi()

        val notifId = 20000 + (notification.uid and 0x7FFF)
        val isCall = notification.categoryId == AncsProtocol.CATEGORY_INCOMING_CALL

        // Vibrate watch on incoming notification or call
        vibrateWatch(isCall)

        // Dismiss intent (when user swipes away notification on watch -> sends Negative action to clear on iPhone)
        val dismissIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "com.rafa.gw4bridge.ACTION_DISMISS"
            putExtra(EXTRA_UID, notification.uid)
            putExtra(EXTRA_POSITIVE, false)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            this,
            notifId * 10 + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isCall) CHANNEL_CALLS else CHANNEL_NOTIFICATIONS
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(
                if (isCall) android.R.drawable.sym_call_incoming
                else android.R.drawable.stat_notify_chat
            )
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
            .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setDeleteIntent(dismissPending)

        // Positive Action Button (e.g., Answer Call / Accept / Reply)
        if ((notification.eventFlags and AncsProtocol.FLAG_POSITIVE_ACTION) != 0 || isCall) {
            val posIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "com.rafa.gw4bridge.ACTION_POSITIVE"
                putExtra(EXTRA_UID, notification.uid)
                putExtra(EXTRA_POSITIVE, true)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            val posPending = PendingIntent.getBroadcast(
                this,
                notifId * 10 + 2,
                posIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val posLabel = when {
                isCall -> "Atender"
                notification.positiveLabel.isNotBlank() -> notification.positiveLabel
                else -> "Aceitar"
            }
            builder.addAction(android.R.drawable.ic_menu_call, posLabel, posPending)
        }

        // Negative Action Button (e.g., Reject Call / Clear from iPhone)
        if ((notification.eventFlags and AncsProtocol.FLAG_NEGATIVE_ACTION) != 0 || isCall) {
            val negIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "com.rafa.gw4bridge.ACTION_NEGATIVE"
                putExtra(EXTRA_UID, notification.uid)
                putExtra(EXTRA_POSITIVE, false)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            val negPending = PendingIntent.getBroadcast(
                this,
                notifId * 10 + 3,
                negIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val negLabel = when {
                isCall -> "Recusar"
                notification.negativeLabel.isNotBlank() && !notification.negativeLabel.equals("Clear", true) -> notification.negativeLabel
                else -> "Dispensar no iPhone"
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, negLabel, negPending)
        }

        notificationManager.notify(notifId, builder.build())
    }

    override fun onNotificationRemoved(uid: Int) {
        val notifId = 20000 + (uid and 0x7FFF)
        notificationManager.cancel(notifId)
    }

    private fun vibrateWatch(isCall: Boolean) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (isCall) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1))
                }
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fgChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                "Status da Ponte iPhone",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a conexão Bluetooth ANCS e Apple Health ativa"
            }

            val notifChannel = NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                "Notificações do iPhone",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "WhatsApp, Instagram e demais apps via ANCS"
                enableVibration(true)
            }

            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Ligações do iPhone",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Chamadas recebidas no iPhone"
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(fgChannel)
            notificationManager.createNotificationChannel(notifChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    private fun buildForegroundStatusNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("GW4 ↔ iPhone 15 Pro")
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }
}
