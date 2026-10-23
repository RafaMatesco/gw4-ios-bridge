package com.rafa.gw4bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getIntExtra(BridgeService.EXTRA_UID, -1)
        val positive = intent.getBooleanExtra(BridgeService.EXTRA_POSITIVE, false)
        val notifId = intent.getIntExtra(BridgeService.EXTRA_NOTIF_ID, -1)

        val svcIntent = Intent(context, BridgeService::class.java).apply {
            action = BridgeService.ACTION_PERFORM_ANCS
            putExtra(BridgeService.EXTRA_UID, uid)
            putExtra(BridgeService.EXTRA_POSITIVE, positive)
            putExtra(BridgeService.EXTRA_NOTIF_ID, notifId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
