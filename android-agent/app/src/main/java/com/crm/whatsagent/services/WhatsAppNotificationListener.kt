package com.crm.whatsagent.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Parcelable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.crm.whatsagent.models.Direction
import com.crm.whatsagent.models.MessageEvent
import com.crm.whatsagent.util.IdempotencyKey
import com.crm.whatsagent.util.PhoneNormalizer
import com.crm.whatsagent.models.DeviceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val TAG = "WA_NLS"

/** WhatsApp package names (regular + Business). */
private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * NotificationListenerService intercepts WhatsApp notifications to extract
 * incoming messages without modifying WhatsApp.
 *
 * ─── What we get ──────────────────────────────────────────────
 *  • EXTRA_TITLE          → contact name (or group name)
 *  • EXTRA_TEXT           → latest message preview (may be truncated at ~100 chars)
 *  • EXTRA_BIG_TEXT       → single expanded message
 *  • EXTRA_MESSAGES       → MessagingStyle array (Android 7+, up to last N messages)
 *    Each entry is a Bundle with keys: "text", "type" (0=incoming,1=outgoing), "time", "sender"
 *
 * ─── Limitations ──────────────────────────────────────────────
 *  • If the user has WhatsApp open (foreground), WA suppresses the notification
 *    → AccessibilityService covers that case.
 *  • Content may be "1 new message" when user has notification content hidden in WA privacy settings.
 *  • Group messages: EXTRA_TITLE = group name; sender per-message in EXTRA_MESSAGES.
 *
 * ─── Android 10-14 notes ──────────────────────────────────────
 *  Android 10–13  : Works as expected; BIND_NOTIFICATION_LISTENER_SERVICE granted via settings.
 *  Android 14     : No new restrictions for notification listener; still works.
 *  Battery saver  : NLS is system-managed; not killed by battery optimisation.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile var instance: WhatsAppNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListener connected")
        // Immediately scan all existing notifications to pre-fill the reply cache.
        // On Samsung, per-chat notifications may only be visible right after bind.
        scope.launch {
            kotlinx.coroutines.delay(500)
            cacheAllActiveReplyActions()
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    /** Track processed notification keys to avoid re-sending the same data. */
    private val processedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Cache of recent per-chat reply actions, keyed by normalised contact name AND phone.
     * WhatsApp removes per-chat notifications when the chat is opened by the user, so we
     * keep the latest known reply PendingIntent + RemoteInput around so we can still
     * fire a reply from the background later.
     *
     * NOTE: WhatsApp's reply PendingIntents are typically reusable for ~hours; if WA
     * regenerates them on each new message we'll just overwrite our cached entry.
     */
    data class CachedReply(
        val action: Notification.Action,
        val remoteInput: RemoteInput,
        val capturedAt: Long,
    )
    private val replyCache = java.util.concurrent.ConcurrentHashMap<String, CachedReply>()
    private val replyCacheTtlMs = 24 * 60 * 60 * 1000L // 24 hours

    private fun cacheKey(s: String): String = s.trim().lowercase()

    /** Pending replies waiting for the next per-chat notification from the contact. */
    private data class PendingReply(
        val cmd: DeviceCommand,
        val callback: (Boolean) -> Unit,
        val queuedAt: Long,
    )
    private val pendingReplies = java.util.concurrent.ConcurrentLinkedQueue<PendingReply>()
    private val pendingTtlMs = 15_000L // 15 seconds — then fall back to a11y

    /**
     * Queue a send command to be retried as soon as a notification arrives that
     * gives us a usable RemoteInput action for this contact. The callback fires
     * once with the final outcome.
     */
    fun queuePendingReply(cmd: DeviceCommand, callback: (Boolean) -> Unit) {
        // First try once more in case a cached entry was just refreshed.
        if (sendReplyViaNotification(cmd.contactName, cmd.content, cmd.contactPhone)) {
            callback(true); return
        }
        pendingReplies.add(PendingReply(cmd, callback, System.currentTimeMillis()))
        Log.d(TAG, "queued pending reply for '${cmd.contactName}' (queue size=${pendingReplies.size})")
        // Schedule timeout cleanup.
        scope.launch {
            kotlinx.coroutines.delay(pendingTtlMs)
            val now = System.currentTimeMillis()
            val expired = pendingReplies.filter { now - it.queuedAt >= pendingTtlMs }
            expired.forEach {
                if (pendingReplies.remove(it)) it.callback(false)
            }
        }
    }

    /** Try to flush any pending replies that match the contact name / phone we just cached. */
    private fun tryFlushPendingFor(contactName: String?, sbn: StatusBarNotification) {
        if (pendingReplies.isEmpty()) return

        // After cacheAllActiveReplyActions, we might have cached reply actions for
        // pending contacts. Try ALL pending — not just matching name/phone.
        val iter = pendingReplies.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val ok = sendReplyViaNotification(p.cmd.contactName, p.cmd.content, p.cmd.contactPhone)
            if (ok) {
                iter.remove()
                Log.i(TAG, "flushed pending reply for '${p.cmd.contactName}' → true")
                p.callback(true)
            }
        }
    }

    private fun cacheReplyAction(contactName: String?, sbn: StatusBarNotification) {
        val action = findReplyAction(sbn.notification) ?: return
        val ri = action.remoteInputs?.firstOrNull() ?: return
        val entry = CachedReply(action, ri, System.currentTimeMillis())
        if (!contactName.isNullOrBlank()) {
            replyCache[cacheKey(contactName)] = entry
            Log.d(TAG, "cached reply action for '$contactName'")
        }
        // Also key by phone extracted from the notification key, if any.
        extractPhoneFromKey(sbn.key)?.let { phone ->
            replyCache[cacheKey(PhoneNormalizer.digits(phone))] = entry
        }
        // Cleanup expired entries opportunistically.
        val cutoff = System.currentTimeMillis() - replyCacheTtlMs
        replyCache.entries.removeIf { it.value.capturedAt < cutoff }
    }

    /**
     * Scan all currently active notifications and cache reply actions from per-chat ones.
     * On Samsung and some OEMs, onNotificationPosted is only called for the group summary,
     * but per-chat children with reply actions ARE present in getActiveNotifications().
     */
    private fun cacheAllActiveReplyActions() {
        val actives = try { activeNotifications } catch (_: Exception) { return }
        Log.d(TAG, "activeNotifications total: ${actives.size}")
        var cachedCount = 0
        var waTotal = 0
        for (sbn in actives) {
            if (sbn.packageName !in WA_PACKAGES) continue
            waTotal++
            val flags = sbn.notification.flags
            val isSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
            val hasExtras = sbn.notification.extras != null
            val actionCount = sbn.notification.actions?.size ?: 0
            Log.d(TAG, "activeWA[$waTotal]: key=${sbn.key} tag=${sbn.tag} isSummary=$isSummary hasExtras=$hasExtras actions=$actionCount flags=0x${Integer.toHexString(flags)}")
            // Cache from ANY notification (including summary if it has a reply action)
            val title = sbn.notification.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val replyAction = findReplyAction(sbn.notification)
            if (title != null && replyAction != null) {
                cacheReplyAction(title, sbn)
                cachedCount++
            }
        }
        Log.i(TAG, "cacheAllActiveReplyActions: $waTotal WA notifications, $cachedCount with reply actions, cache size=${replyCache.size}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WA_PACKAGES) return
        if (sbn.notification.extras == null) return

        val isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        Log.d(TAG, "onNotificationPosted: key=${sbn.key} tag=${sbn.tag} isSummary=$isSummary")

        // Dump ALL extras keys for investigation
        val extras = sbn.notification.extras
        val keyList = extras.keySet().joinToString(",")
        Log.d(TAG, "  extras keys: $keyList")

        // Dump notification actions to find reply action.
        val actions = sbn.notification.actions
        if (actions != null) {
            for ((i, a) in actions.withIndex()) {
                val riCount = a.remoteInputs?.size ?: 0
                @Suppress("DEPRECATION")
                val sem = try { a.semanticAction } catch (_: Throwable) { 0 }
                Log.d(TAG, "  action[$i] title='${a.title}' remoteInputs=$riCount semanticAction=$sem")
            }
        } else {
            Log.d(TAG, "  no actions on this notification")
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            Log.d(TAG, "  EXTRA_TEXT_LINES: ${textLines.size} lines")
            for ((i, line) in textLines.withIndex()) {
                Log.d(TAG, "    [$i] ${line.toString().take(80)}")
            }
        }

        // Always try to cache a reply action from ANY notification (summary or per-chat).
        // For summary notifications WhatsApp sometimes still attaches a reply action that
        // routes to the most-recent chat; for per-chat notifications it always does.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        cacheReplyAction(title, sbn)

        if (isSummary) {
            // Samsung / some OEMs don't fire onNotificationPosted for per-chat children
            // but they ARE present in activeNotifications. Scan and cache their reply
            // actions so RemoteInput can work.
            cacheAllActiveReplyActions()
        }

        tryFlushPendingFor(title, sbn)

        if (isSummary) {
            // Try to extract messages from summary's InboxStyle / MessagingStyle
            scope.launch { processSummaryNotification(sbn) }
        } else {
            scope.launch { processNotification(sbn) }
        }
    }

    /**
     * WhatsApp summary notifications contain InboxStyle text lines like:
     *   "Alice: Hello"
     *   "Bob: 2 messages"
     *   "Group Chat: Carol: Hi everyone"
     */
    private fun processSummaryNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras

        // Primary: MessagingStyle inside summary
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            Log.d(TAG, "summary has ${messages.size} MessagingStyle messages")
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "WA"
            processMessagingStyleMessages(messages, title, sbn.packageName)
            return
        }

        // Fallback: InboxStyle text lines "Sender: message"
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return
        var count = 0
        for (raw in lines) {
            val line = raw.toString()
            // Dedup by line CONTENT only (not postTime) — WA reposts the summary
            // with the same lines + new postTime on every update, which would
            // otherwise re-dispatch every line over and over.
            val dedupKey = "line:$line"
            val now = System.currentTimeMillis()
            val prev = processedKeys.put(dedupKey, now)
            // Skip if we've seen this exact line within the last hour.
            if (prev != null && now - prev < 3_600_000) continue

            val (sender, content) = parseInboxLine(line) ?: continue
            if (isSystemString(content)) continue

            val phone = if (looksLikePhone(sender)) sender else ""
            val event = buildEvent(
                contactName  = sender,
                contactPhone = phone,
                content      = content,
                timestampMs  = sbn.postTime,
            ) ?: continue
            dispatchToAgentService(event)
            count++
        }
        Log.d(TAG, "summary: dispatched $count events from InboxStyle")

        val cutoff = System.currentTimeMillis() - 3_600_000
        processedKeys.entries.removeIf { it.value < cutoff }
    }

    /** Parse "Sender: content" from InboxStyle line. */
    private fun parseInboxLine(line: String): Pair<String, String>? {
        val idx = line.indexOf(": ")
        if (idx <= 0 || idx >= line.length - 2) return null
        val sender = line.substring(0, idx).trim()
        val content = line.substring(idx + 2).trim()
        if (sender.isBlank() || content.isBlank()) return null
        return sender to content
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val contactName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (contactName == null) {
            Log.d(TAG, "no EXTRA_TITLE, skipping key=${sbn.key}")
            return
        }

        // Cache the reply PendingIntent so we can answer this contact later from the background,
        // even after WhatsApp clears the notification.
        cacheReplyAction(contactName, sbn)
        // If we have a queued reply waiting for this contact, fire it now.
        tryFlushPendingFor(contactName, sbn)

        val extraText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val extraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        Log.d(TAG, "title='$contactName' text='${extraText?.take(60)}' bigText='${extraBigText?.take(60)}'")

        // Try MessagingStyle first (richest data, available API 24+)
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            Log.d(TAG, "MessagingStyle: ${messages.size} messages")
            processMessagingStyleMessages(messages, contactName, sbn.packageName)
            return
        }

        // Fallback: single message from EXTRA_TEXT
        val text = extraBigText ?: extraText ?: run {
            Log.d(TAG, "no text at all, skipping")
            return
        }

        // Skip system strings that are not real messages.
        if (isSystemString(text)) {
            Log.d(TAG, "system string skipped: '${text.take(60)}'")
            return
        }

        val event = buildEvent(
            contactName  = contactName,
            contactPhone = extractPhoneFromKey(sbn.key) ?: "",
            content      = text,
            timestampMs  = sbn.postTime,
        ) ?: return

        dispatchToAgentService(event)
    }

    @Suppress("DEPRECATION")
    private fun processMessagingStyleMessages(
        rawMessages: Array<Parcelable>,
        notifTitle: String,
        packageName: String,
    ) {
        for (raw in rawMessages) {
            val bundle = raw as? Bundle ?: continue
            val text   = bundle.getCharSequence("text")?.toString() ?: continue
            if (isSystemString(text)) {
                Log.d(TAG, "MessagingStyle: skipping system string '${text.take(60)}'")
                continue
            }

            val sender  = bundle.getCharSequence("sender")?.toString() ?: notifTitle
            val timeMs  = bundle.getLong("time", System.currentTimeMillis())
            val msgType = bundle.getInt("type", 0) // 0 = incoming, 1 = outgoing
            val phone   = if (looksLikePhone(sender)) sender else ""

            val event = buildEvent(
                contactName  = sender,
                contactPhone = phone,
                content      = text,
                timestampMs  = timeMs,
                direction    = if (msgType == 1) Direction.OUT else Direction.IN,
            ) ?: continue

            dispatchToAgentService(event)
        }
    }

    private fun buildEvent(
        contactName: String,
        contactPhone: String,
        content: String,
        timestampMs: Long,
        direction: Direction = Direction.IN,
    ): MessageEvent? {
        if (content.isBlank()) return null
        val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestampMs))
        val phone = PhoneNormalizer.normalize(contactPhone)
        val idemKey = IdempotencyKey.compute(
            AgentForegroundService.deviceId, phone, content, ts
        )
        return MessageEvent(
            idempotencyKey = idemKey,
            contactName    = contactName,
            contactPhone   = phone,
            content        = content,
            direction      = direction,
            timestamp      = ts,
        )
    }

    private fun dispatchToAgentService(event: MessageEvent) {
        Log.d(TAG, "dispatching event: ${event.contactName} → ${event.content.take(40)}")
        AgentForegroundService.instance?.enqueueEvent(event)
    }

    /** Returns true if the string looks like a phone number (7+ digits). */
    private fun looksLikePhone(s: String): Boolean {
        return s.count { it.isDigit() } >= 7
    }

    /** Heuristic skip for WhatsApp system notification strings. */
    private fun isSystemString(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("messages") && lower.contains("new") ||
            lower == "ongoing voice call" ||
            lower.startsWith("missed voice call") ||
            lower.startsWith("missed video call") ||
            lower.contains("tap to view") ||
            lower.contains("calling…") ||
            // Russian
            lower.contains("сообщений из") ||
            lower.contains("сообщения из") ||
            lower.contains("новых сообщений") ||
            lower.contains("новое сообщение") ||
            lower.contains("входящий звонок") ||
            lower.contains("пропущенный") ||
            lower.contains("нажмите для просмотра") ||
            // Generic: matches "N messages from M chats" pattern in any language
            Regex("""\d+\s+\S+\s+\S+\s+\d+\s+\S+""").containsMatchIn(lower)
    }

    /**
     * WhatsApp notification keys contain the JID (phone@s.whatsapp.net).
     * Example key: "0|com.whatsapp|0|null|+14155551234@s.whatsapp.net|..."
     */
    private fun extractPhoneFromKey(key: String): String? {
        return Regex("""(\+?\d{7,15})@s\.whatsapp\.net""")
            .find(key)
            ?.groupValues
            ?.getOrNull(1)
    }

    // ─── Reply via RemoteInput (no WhatsApp UI needed) ────────

    /**
     * Sends a reply using the RemoteInput action from a WhatsApp notification.
     * Looks first in currently active notifications, then in the cache of recently
     * seen reply actions (so we can still reply after WA clears the notification
     * when the user opens the chat). Returns true if a reply was fired.
     */
    fun sendReplyViaNotification(contactName: String, text: String, contactPhone: String = ""): Boolean {
        // Proactively scan all active notifications so we have the freshest cache.
        cacheAllActiveReplyActions()

        val target = findActiveReplyAction(contactName, contactPhone)
        if (target != null) {
            return fireReply(target.first, target.second, contactName, text)
        }

        // Fall back to cached reply action for this contact.
        val cached = lookupCachedReply(contactName, contactPhone)
        if (cached != null) {
            Log.d(TAG, "using CACHED reply action for '$contactName' (age=${(System.currentTimeMillis() - cached.capturedAt) / 1000}s)")
            return fireReply(cached.action, cached.remoteInput, contactName, text)
        }

        Log.w(TAG, "no reply action available for '$contactName' / '$contactPhone' (active=${activeNotifications?.size ?: 0}, cache=${replyCache.size})")
        return false
    }

    /** Search active notifications for a per-chat reply action matching the contact. */
    private fun findActiveReplyAction(contactName: String, contactPhone: String): Pair<Notification.Action, RemoteInput>? {
        val actives = try { activeNotifications ?: return null } catch (_: Exception) { return null }
        val nameKey = cacheKey(contactName)
        val phoneDigits = if (contactPhone.isNotBlank()) PhoneNormalizer.digits(contactPhone) else ""

        // First pass: per-chat notifications (skip summaries).
        val sbn = actives.firstOrNull { sbn ->
            if (sbn.packageName !in WA_PACKAGES) return@firstOrNull false
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@firstOrNull false
            val title = sbn.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val titleKey = cacheKey(title)
            val nameMatch = nameKey.isNotBlank() && (titleKey == nameKey || titleKey.contains(nameKey) || nameKey.contains(titleKey))
            val phoneMatch = phoneDigits.isNotBlank() && (sbn.key.contains(phoneDigits) || PhoneNormalizer.digits(title) == phoneDigits)
            nameMatch || phoneMatch
        }
        if (sbn != null) {
            val action = findReplyAction(sbn.notification) ?: return null
            val ri = action.remoteInputs?.firstOrNull() ?: return null
            return action to ri
        }

        return null
    }

    private fun lookupCachedReply(contactName: String, contactPhone: String): CachedReply? {
        val nameKey = cacheKey(contactName)
        val phoneKey = if (contactPhone.isNotBlank()) cacheKey(PhoneNormalizer.digits(contactPhone)) else ""
        // Exact match by name first, then phone, then fuzzy contains over keys.
        replyCache[nameKey]?.let { return it }
        if (phoneKey.isNotBlank()) replyCache[phoneKey]?.let { return it }
        if (nameKey.isNotBlank()) {
            val fuzzy = replyCache.entries.firstOrNull { (k, _) ->
                k.contains(nameKey) || nameKey.contains(k)
            }
            if (fuzzy != null) return fuzzy.value
        }
        return null
    }

    private fun fireReply(
        action: Notification.Action,
        remoteInput: RemoteInput,
        contactName: String,
        text: String,
    ): Boolean {
        return try {
            val intent = Intent()
            val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "reply sent via RemoteInput to '$contactName' (${text.take(40)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "fireReply failed: ${e.message}")
            false
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        // Prefer action that declares semantic REPLY; fallback to first with RemoteInput.
        for (a in actions) {
            if (a.remoteInputs.isNullOrEmpty()) continue
            @Suppress("DEPRECATION")
            val isReply = try {
                a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            } catch (_: Throwable) { false }
            if (isReply) return a
        }
        return actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }
}
