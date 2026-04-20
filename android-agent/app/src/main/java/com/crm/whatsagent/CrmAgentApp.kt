package com.crm.whatsagent

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.crm.whatsagent.services.AgentForegroundService

class CrmAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startAgentService()
    }

    private fun startAgentService() {
        val intent = Intent(this, AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i("CrmAgentApp", "AgentForegroundService start requested")
    }
}
