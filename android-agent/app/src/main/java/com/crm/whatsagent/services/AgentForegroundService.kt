package com.crm.whatsagent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.crm.whatsagent.BuildConfig
import com.crm.whatsagent.MainActivity
import com.crm.whatsagent.R
import com.crm.whatsagent.models.DeviceCommand
import com.crm.whatsagent.models.MessageEvent
import com.crm.whatsagent.models.StatusUpdate
import com.crm.whatsagent.network.AgentWebSocketClient
import com.crm.whatsagent.queue.LocalMessageQueue
import kotlinx.coroutines.*

private const val TAG              = "AgentService"
private const val NOTIF_CHANNEL_ID = "crm_agent_channel"
private const val FOREGROUND_ID    = 1001

/**
 * AgentForegroundService is the heart of the Android agent.
 *
 * Responsibilities:
 *  ─ Keeps the process alive via startForeground() (Android 8+ requirement)
 *  ─ Maintains the WebSocket connection via [AgentWebSocketClient]
 *  ─ Enqueues captured MessageEvents to [LocalMessageQueue] (SQLite)
 *  ─ Flushes the local queue to the backend (with retry on failure)
 *  ─ Receives send-commands from the backend and delegates to [WhatsAppAccessibilityService]
 *  ─ Sends heartbeats every 30 s so the backend knows the device is online
 *
 * ─── Process survival on Android 10–14 ───────────────────────
 *  • START_STICKY        → if the system kills the service, it restarts automatically.
 *  • startForeground()   → promoted to foreground; much harder for OS to kill.
 *  • BootReceiver        → restarts the service after device reboot.
 *  • Battery optimisation exemption requested from the user in MainActivity.
 *  • Doze mode           → WS connection may be suspended; OkHttp ping/pong
 *    re-establishes it when network returns (NetworkCallback handles this too).
 */
class AgentForegroundService : Service() {

    companion object {
        @Volatile var instance: AgentForegroundService? = null
        /** Device ID inserted by the backend at registration time. */
        var deviceId: String = BuildConfig.DEVICE_TOKEN.take(8) // placeholder; real ID in prefs
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var wsClient: AgentWebSocketClient
    private lateinit var localQueue: LocalMessageQueue

    private var flushJob: Job? = null
    private var heartbeatJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        localQueue = LocalMessageQueue(applicationContext)

        wsClient = AgentWebSocketClient(
            onCommand       = ::handleIncomingCommand,
            onConnected     = ::onWsConnected,
            onDisconnected  = ::onWsDisconnected,
        )

        startForeground(FOREGROUND_ID, buildForegroundNotification())
        wsClient.connect()

        startHeartbeat()
        Log.i(TAG, "AgentForegroundService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the service running even after the user swipes the app away.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not a bound service

    override fun onDestroy() {
        instance = null
        scope.cancel()
        wsClient.disconnect()
        super.onDestroy()
        Log.i(TAG, "AgentForegroundService destroyed — will restart via START_STICKY")
    }

    // ─── Public API (called from NLS / Accessibility) ─────────

    /**
     * Short-lived dedup map: "contactPhone:content" → timestamp.
     * Prevents the same message being enqueued twice when both NLS and a11y
     * pick it up within a short window (they produce different idempotency keys
     * because their timestamps differ).
     */
    private val recentContentKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Enqueues a captured WhatsApp message for delivery to the backend. */
    fun enqueueEvent(event: MessageEvent) {
        val contentKey = "${event.contactPhone}:${event.content}"
        val now = System.currentTimeMillis()
        val prev = recentContentKeys.put(contentKey, now)
        if (prev != null && now - prev < 5_000) {
            Log.d(TAG, "dedup: skipping duplicate event within 5s: ${event.content.take(30)}")
            return
        }
        // Clean old entries periodically.
        recentContentKeys.entries.removeIf { now - it.value > 60_000 }

        scope.launch {
            localQueue.enqueue(event)
            flushQueue()
        }
    }

    /** Called by AccessibilityService when a send-command completes. */
    fun notifyCommandStatus(cmd: DeviceCommand, status: String, error: String? = null) {
        scope.launch {
            wsClient.sendStatusUpdate(
                StatusUpdate(
                    commandId = cmd.commandId,
                    status    = status,
                    error     = error,
                )
            )
        }
    }

    // ─── WebSocket callbacks ──────────────────────────────────

    private fun onWsConnected() {
        Log.i(TAG, "WS connected — flushing local queue")
        scope.launch { flushQueue() }
    }

    private fun onWsDisconnected() {
        Log.w(TAG, "WS disconnected")
        // Reconnection is handled inside AgentWebSocketClient automatically.
    }

    // ─── Queue flush ──────────────────────────────────────────

    /**
     * Attempts to deliver all pending events in the local queue.
     * Runs inside a coroutine; safe to call concurrently (idempotent).
     */
    private suspend fun flushQueue() {
        if (!wsClient.isConnected) return

        flushJob?.cancel()
        flushJob = scope.launch {
            val pending = localQueue.peek(limit = 50)
            Log.d(TAG, "flushing ${pending.size} pending events")

            for (entry in pending) {
                val event = localQueue.parseEvent(entry)
                if (event == null) {
                    localQueue.ack(entry.id) // corrupt entry → drop
                    continue
                }
                val sent = wsClient.sendEvent(event)
                if (sent) {
                    localQueue.ack(entry.id)
                    Log.d(TAG, "event acked: ${event.idempotencyKey.take(12)}…")
                } else {
                    localQueue.incrementRetry(entry.id)
                    Log.w(TAG, "WS send failed; will retry (attempt ${entry.retryCount + 1})")
                    break // stop flush if connection dropped mid-batch
                }
                delay(50) // small throttle to avoid overwhelming the backend
            }
        }
    }

    // ─── Heartbeat ────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                wsClient.sendHeartbeat()
            }
        }
    }

    // ─── Command handling (Backend → Device) ──────────────────

    private fun handleIncomingCommand(cmd: DeviceCommand) {
        Log.i(TAG, "command received: ${cmd.commandType} → ${cmd.contactName.ifBlank { cmd.contactPhone }}")
        when (cmd.commandType) {
            "send_message" -> {
                // Priority 1: silent reply via NLS RemoteInput — instant, no UI.
                // If no cached reply action is available, we fall through immediately
                // (do NOT wait for a future notification; on Samsung NLS is heavily
                // restricted and waiting just adds dead time).
                val nls = WhatsAppNotificationListener.instance
                if (nls != null) {
                    val ok = nls.sendReplyViaNotification(
                        contactName  = cmd.contactName,
                        text         = cmd.content,
                        contactPhone = cmd.contactPhone,
                    )
                    if (ok) {
                        notifyCommandStatus(cmd, "sent")
                        return
                    }
                    Log.d(TAG, "RemoteInput unavailable → a11y")
                }

                // Priority 2: AccessibilityService. Commands are serialized
                // inside the service — safe to call from anywhere, any order.
                sendViaAccessibility(cmd)
            }
            else -> Log.w(TAG, "unknown command type: ${cmd.commandType}")
        }
    }

    private fun sendViaAccessibility(cmd: DeviceCommand) {
        val a11y = WhatsAppAccessibilityService.instance
        if (a11y != null) {
            a11y.enqueueSendCommand(cmd)
            // Note: status will be sent from the A11y worker when the send
            // actually completes (success or failure).
        } else {
            Log.e(TAG, "AccessibilityService unavailable")
            notifyCommandStatus(cmd, "failed", "no send path available")
        }
    }

    // ── Foreground notification ──────────────────────────────

    private fun buildForegroundNotification(): Notification {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "CRM Agent",
                NotificationManager.IMPORTANCE_LOW, // silent — just keeps the process alive
            ).apply { description = "WhatsApp CRM sync" }
            mgr.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("CRM Agent")
            .setContentText("Connected — monitoring WhatsApp")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
