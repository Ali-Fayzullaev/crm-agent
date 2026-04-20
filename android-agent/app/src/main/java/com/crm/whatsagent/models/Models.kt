package com.crm.whatsagent.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Direction of a WhatsApp message from the device's perspective. */
enum class Direction { IN, OUT }

/** A WhatsApp message event captured on-device. */
@Parcelize
data class MessageEvent(
    /** SHA-256(deviceId + contactPhone + content + epoch) — used for dedup on backend. */
    val idempotencyKey: String,
    /** WhatsApp internal ID, if extractable. */
    val externalId: String = "",
    val contactName: String,
    val contactPhone: String,
    val content: String,
    val contentType: String = "text",
    val direction: Direction,
    /** ISO-8601 timestamp from the device. */
    val timestamp: String,
) : Parcelable

/** A command received from the backend via WebSocket. */
data class DeviceCommand(
    val commandId: String,
    val commandType: String,       // "send_message"
    val contactPhone: String,
    val contactName: String,
    val content: String,
    val conversationId: String,
)

/** Status acknowledgement sent from device → backend. */
data class StatusUpdate(
    val commandId: String? = null,
    val idempotencyKey: String? = null,
    val status: String,            // sent | delivered | read | failed
    val error: String? = null,
)

// ─── WebSocket envelope ───────────────────────────────────────

data class WsEnvelope(
    val type: String,
    val payload: Any,
)

object EventType {
    const val MESSAGE_RECEIVED = "message_received"
    const val MESSAGE_SENT     = "message_sent"
    const val STATUS_UPDATE    = "status_update"
    const val HEARTBEAT        = "heartbeat"
    const val SEND_MESSAGE     = "send_message"
}
