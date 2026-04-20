package com.crm.whatsagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crm.whatsagent.services.AgentForegroundService

private const val TAG = "MainActivity"

/**
 * Single-screen activity that:
 *  1. Shows setup status (permissions + service health)
 *  2. Guides the user through granting required permissions
 *  3. Can be minimised — the service runs in the background
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()
        ensureServiceRunning()
    }

    private fun checkAndRequestPermissions() {
        // 1. Battery optimisation exemption
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimisation")
                .setMessage(
                    "To ensure the CRM Agent runs reliably in the background, " +
                    "please disable battery optimisation for this app."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("Later", null)
                .show()
        }

        // 2. Notification listener check
        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage(
                    "Please enable Notification Access for CRM Agent.\n\n" +
                    "Settings → Apps → Special app access → Notification access → CRM Agent"
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }

        // 3. Accessibility service check
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Service Required")
                .setMessage(
                    "Please enable Accessibility access so CRM Agent can send replies via WhatsApp.\n\n" +
                    "Settings → Accessibility → Installed apps → CRM Agent"
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }

        // 4. Notification permission (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(packageName)
    }

    private fun ensureServiceRunning() {
        if (AgentForegroundService.instance == null) {
            val intent = Intent(this, AgentForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "service started from MainActivity")
        } else {
            Toast.makeText(this, "CRM Agent running ✓", Toast.LENGTH_SHORT).show()
        }
    }
}
