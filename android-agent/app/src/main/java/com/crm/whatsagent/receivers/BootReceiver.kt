package com.crm.whatsagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.crm.whatsagent.services.AgentForegroundService

private const val TAG = "BootReceiver"

/**
 * Restarts [AgentForegroundService] after the device boots or after the app is updated.
 *
 * Required permissions in manifest:
 *   android.permission.RECEIVE_BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON", // HTC / Samsung variant
            )
        ) return

        Log.i(TAG, "Boot/update broadcast received — starting AgentForegroundService")

        val serviceIntent = Intent(context, AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
