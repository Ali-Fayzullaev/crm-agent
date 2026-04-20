package com.crm.whatsagent.network

import android.util.Log
import com.crm.whatsagent.BuildConfig
import com.crm.whatsagent.models.DeviceCommand
import com.crm.whatsagent.models.EventType
import com.crm.whatsagent.models.MessageEvent
import com.crm.whatsagent.models.StatusUpdate
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

private const val TAG = "AgentWsClient"

/**
 * Persistent WebSocket client that:
 *  - maintains a connection to the backend WS endpoint
 *  - sends MessageEvent frames to the backend
 *  - receives SendMessageCmd frames and dispatches them to [onCommand]
 *  - performs exponential-backoff reconnection on failure
 *  - sends heartbeat pings every 30 s
 */
class AgentWebSocketClient(
    private val onCommand: (DeviceCommand) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // disable — WebSocket is long-lived
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // built-in ping / pong
        .retryOnConnectionFailure(true)
        .build()

    private val request = Request.Builder()
        .url(BuildConfig.BACKEND_WS_URL)
        .header("Authorization", "Bearer ${BuildConfig.DEVICE_TOKEN}")
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    private var reconnectDelayMs = 2_000L
    private val maxDelayMs = 60_000L

    // ─── Lifecycle ────────────────────────────────────────────

    fun connect() {
        scope.launch { connectWithRetry() }
    }

    fun disconnect() {
        scope.cancel()
        ws?.close(1000, "agent shutdown")
        ws = null
    }

    private suspend fun connectWithRetry() {
        while (scope.isActive) {
            Log.i(TAG, "connecting to ${BuildConfig.BACKEND_WS_URL}")
            ws = client.newWebSocket(request, listener)
            // Wait until the connection closes; the listener resumes this via reconnectSignal.
            reconnectSignal.receive()
            if (!scope.isActive) return
            Log.i(TAG, "reconnecting in ${reconnectDelayMs}ms")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(maxDelayMs)
        }
    }

    private val reconnectSignal = kotlinx.coroutines.channels.Channel<Unit>(1)

    // ─── Send API ─────────────────────────────────────────────

    /** Sends a message event to the backend. Returns false if WebSocket is not open. */
    fun sendEvent(event: MessageEvent): Boolean {
        if (!connected) return false
        val payload = mapOf(
            "idempotency_key" to event.idempotencyKey,
            "external_id"     to event.externalId,
            "contact_name"    to event.contactName,
            "contact_phone"   to event.contactPhone,
            "content"         to event.content,
            "content_type"    to event.contentType,
            "direction"       to event.direction.name.lowercase(),
            "timestamp"       to event.timestamp,
        )
        val envelope = mapOf("type" to EventType.MESSAGE_RECEIVED, "payload" to payload)
        return ws?.send(gson.toJson(envelope)) ?: false
    }

    fun sendStatusUpdate(update: StatusUpdate): Boolean {
        if (!connected) return false
        val envelope = mapOf("type" to EventType.STATUS_UPDATE, "payload" to update)
        return ws?.send(gson.toJson(envelope)) ?: false
    }

    fun sendHeartbeat(): Boolean {
        if (!connected) return false
        val envelope = mapOf("type" to EventType.HEARTBEAT, "payload" to emptyMap<String, Any>())
        return ws?.send(gson.toJson(envelope)) ?: false
    }

    val isConnected: Boolean get() = connected

    // ─── WebSocket listener ───────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open")
            connected = true
            reconnectDelayMs = 2_000L
            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val env = gson.fromJson(text, JsonObject::class.java)
                val type = env.get("type")?.asString ?: return
                val payloadEl = env.get("payload") ?: return

                when (type) {
                    EventType.SEND_MESSAGE -> {
                        val cmd = gson.fromJson(payloadEl, SendMessagePayload::class.java)
                        onCommand(
                            DeviceCommand(
                                commandId      = cmd.command_id,
                                commandType    = EventType.SEND_MESSAGE,
                                contactPhone   = cmd.contact_phone,
                                contactName    = cmd.contact_name,
                                content        = cmd.content,
                                conversationId = cmd.conversation_id,
                            )
                        )
                    }
                    else -> Log.d(TAG, "unhandled server event: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "parse error: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $code $reason")
            finalize()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            finalize()
        }

        private fun finalize() {
            connected = false
            onDisconnected()
            reconnectSignal.trySend(Unit)
        }
    }

    // ─── Internal DTO for deserialising server command ────────────

    private data class SendMessagePayload(
        val command_id:      String = "",
        val contact_phone:   String = "",
        val contact_name:    String = "",
        val content:         String = "",
        val conversation_id: String = "",
    )
}
