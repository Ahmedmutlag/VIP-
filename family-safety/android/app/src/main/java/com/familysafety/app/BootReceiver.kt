package com.familysafety.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("family_safety", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "") ?: ""
            if (serverUrl.isNotBlank()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FamilySafetyService::class.java)
                )
            }
        }
    }
}
