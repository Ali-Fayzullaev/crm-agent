package com.crm.whatsagent

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Transparent activity used to wake the device, dismiss the lock-screen,
 * and immediately launch a target intent (typically WhatsApp) so the
 * AccessibilityService can drive the UI.
 *
 * Required because foreground services on Android 10+ cannot launch
 * activities while the screen is off / keyguard is up. A transient
 * activity with `turnScreenOn` + `showWhenLocked` flags can.
 *
 * The screen-lock must NOT be secured (no PIN/pattern/password) for full
 * automation. With a secure lock, [KeyguardManager.requestDismissKeyguard]
 * shows the unlock prompt to the user.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Acquire wake lock long enough for WhatsApp to come up.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "crm:wake",
        )
        wl.acquire(10_000)

        // Ask system to dismiss keyguard (works without prompt only when no secure lock).
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, null)
        }

        // Launch the target intent passed in EXTRA_LAUNCH (the WhatsApp deep link or main).
        val launch: Intent? = intent?.getParcelableExtra(EXTRA_LAUNCH)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Slight delay so keyguard dismissal completes before WhatsApp starts.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startActivity(launch)
                } catch (_: Exception) { /* swallow */ }
                if (wl.isHeld) wl.release()
                finishAndRemoveTask()
            }, 250)
        } else {
            if (wl.isHeld) wl.release()
            finishAndRemoveTask()
        }
    }

    companion object {
        const val EXTRA_LAUNCH = "extra_launch_intent"
    }
}
