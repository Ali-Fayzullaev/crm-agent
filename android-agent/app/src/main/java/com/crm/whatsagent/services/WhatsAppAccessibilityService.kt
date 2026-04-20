package com.crm.whatsagent.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.crm.whatsagent.models.DeviceCommand
import com.crm.whatsagent.models.Direction
import com.crm.whatsagent.models.MessageEvent
import com.crm.whatsagent.util.IdempotencyKey
import com.crm.whatsagent.util.PhoneNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "WA_A11y"

/** WhatsApp resource IDs (stable across recent WA versions). */
private const val WA_SEND_BTN         = "com.whatsapp:id/send"
private const val WA_SEND_BTN_B       = "com.whatsapp.w4b:id/send"
private const val WA_INPUT_FIELD      = "com.whatsapp:id/entry"
private const val WA_INPUT_FIELD_B    = "com.whatsapp.w4b:id/entry"
private const val WA_MSG_LIST         = "com.whatsapp:id/conversation_rv_field_scrollparent"

private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * AccessibilityService dual role:
 *
 * 1. READ — captures new WhatsApp messages when the app is in the foreground
 *    (notification listener misses those because WA suppresses notifications).
 *
 * 2. WRITE — automates sending replies:
 *    - Opens WhatsApp with a deep-link (pre-fills contact + message text)
 *    - Waits for the send button to appear in the UI tree
 *    - Performs ACTION_CLICK on the send button
 *
 * ─── Android 10–14 notes ───────────────────────────────────────
 *  • canRetrieveWindowContent = true  → always needed for reading UI tree.
 *  • No special restrictions on AccessibilityService in Android 10–14.
 *  • On Android 11 (API 30) + QUERY_ALL_PACKAGES is not needed because we
 *    declared <queries> in the manifest.
 *  • The service survives Doze; it is system-managed.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WhatsAppAccessibilityService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Single serialized command channel. Commands are processed strictly
     * one-at-a-time in arrival order, ensuring:
     *   • No two send-flows ever run concurrently (no UI races).
     *   • Commands for the same contact are processed as a batch —
     *     after the first one opens the chat, subsequent commands use
     *     the fast path (type + send without re-navigating).
     */
    private val commandChannel = Channel<DeviceCommand>(Channel.UNLIMITED)
    @Volatile private var workerStarted = false
    @Volatile private var sendInProgress = false

    // Tracks last seen message content to avoid re-processing on every UI change.
    private var lastSeenContent = ""

    // Tracks recently sent message texts to suppress re-reading them as incoming.
    private val recentlySent = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val maxRecentlySent = 20

    // ─── Lifecycle ────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        startCommandWorker()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── Event processing ─────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WA_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Triggered whenever the WhatsApp UI tree changes.
                // We use this to read new messages that appeared while WA is foreground.
                if (!sendInProgress) {
                    scope.launch(Dispatchers.IO) { tryReadNewMessages(pkg) }
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // runSendFlow drives the send pipeline now; nothing to do here.
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A click occurred — could be the send button the user tapped themselves.
                // We detect outgoing messages this way as a heuristic backup.
                if (!sendInProgress) {
                    scope.launch(Dispatchers.IO) { tryReadOutgoingMessage(event, pkg) }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    // ─── READ: new messages while WA is in foreground ─────────

    private fun tryReadNewMessages(waPackage: String) {
        val root = rootInActiveWindow ?: return
        try {
            // Find the conversation RecyclerView.
            val listNodes = root.findAccessibilityNodeInfosByViewId(WA_MSG_LIST)
            val listRoot  = listNodes.firstOrNull() ?: return

            val childCount = listRoot.childCount
            if (childCount == 0) return

            val contactPhone = extractCurrentChatPhone(root) ?: ""
            val contactName  = extractCurrentChatName(root) ?: ""

            // Collect texts of all visible bubbles from bottom (newest) up.
            // Stop when we hit a message we've already seen.
            val newMessages = mutableListOf<Pair<String, Boolean>>() // text, isSent
            for (i in (childCount - 1) downTo 0) {
                val child = listRoot.getChild(i) ?: continue
                val text = collectText(child).trim()
                if (text.isBlank()) continue
                if (text == lastSeenContent) break  // already seen this one — stop
                if (recentlySent.any { it == text }) continue  // skip our own automated sends
                val isSent = isOutgoingBubble(child)
                newMessages.add(text to isSent)
            }

            if (newMessages.isEmpty()) return

            // Update lastSeenContent to the newest message (first in list = bottom of chat).
            lastSeenContent = newMessages.first().first

            // Send events oldest-first (reverse since we collected newest-first).
            for ((text, isSent) in newMessages.reversed()) {
                val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                val event = MessageEvent(
                    idempotencyKey = IdempotencyKey.compute(
                        AgentForegroundService.deviceId, contactPhone, text, ts
                    ),
                    contactName  = contactName,
                    contactPhone = contactPhone,
                    content      = text,
                    direction    = if (isSent) Direction.OUT else Direction.IN,
                    timestamp    = ts,
                )
                Log.d(TAG, "a11y read msg: [${event.direction}] ${text.take(40)}")
                AgentForegroundService.instance?.enqueueEvent(event)
            }
        } finally {
            root.recycle()
        }
    }

    private fun tryReadOutgoingMessage(event: AccessibilityEvent, waPackage: String) {
        // If the clicked node looks like the send button, the user sent something.
        val node = event.source ?: return
        val viewId = node.viewIdResourceName ?: return
        if (viewId != WA_SEND_BTN && viewId != WA_SEND_BTN_B) return

        val root = rootInActiveWindow ?: return
        try {
            val inputNodes = root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD)
                .ifEmpty { root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD_B) }
            val text = inputNodes.firstOrNull()?.text?.toString() ?: return
            if (text.isBlank()) return

            val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val contactPhone = extractCurrentChatPhone(root) ?: ""
            val contactName  = extractCurrentChatName(root) ?: ""

            val evt = MessageEvent(
                idempotencyKey = IdempotencyKey.compute(
                    AgentForegroundService.deviceId, contactPhone, text, ts
                ),
                contactName  = contactName,
                contactPhone = contactPhone,
                content      = text,
                direction    = Direction.OUT,
                timestamp    = ts,
            )
            AgentForegroundService.instance?.enqueueEvent(evt)
        } finally {
            root.recycle()
        }
    }

    // ─── WRITE: automate sending via UI ───────────────────────

    /**
     * Enqueues a send command. Commands are processed strictly in order
     * by a single worker coroutine — no concurrency, no races.
     */
    fun enqueueSendCommand(cmd: DeviceCommand) {
        commandChannel.trySend(cmd)
    }

    /**
     * Starts the single consumer coroutine that drains [commandChannel].
     * Runs one command at a time to completion before picking the next.
     */
    private fun startCommandWorker() {
        if (workerStarted) return
        workerStarted = true
        scope.launch(Dispatchers.IO) {
            for (cmd in commandChannel) {
                sendInProgress = true
                try {
                    processCommand(cmd)
                } catch (e: Exception) {
                    Log.e(TAG, "command ${cmd.commandId} crashed: ${e.message}", e)
                    notifyCommandFailed(cmd, "crashed: ${e.message}")
                } finally {
                    sendInProgress = false
                }
                // Small gap between commands so the UI settles.
                delay(300)
            }
        }
    }

    /**
     * Single end-to-end send flow. Strategy (in order):
     *   1. Fast path — if WhatsApp is foreground AND already on the target
     *      chat, just type + send. No navigation, no chat re-open.
     *   2. Otherwise — ensure WA is foreground, navigate back to the chat
     *      list, search for the contact by name, tap first result, type + send.
     * After sending we REMAIN on the chat so subsequent commands for the
     * same contact hit the fast path.
     */
    private suspend fun processCommand(cmd: DeviceCommand) {
        Log.i(TAG, "processCommand ${cmd.commandId} → '${cmd.contactName}'")

        // 1. Fast path: already on the right chat.
        if (typeAndSendIntoOpenChat(cmd)) {
            Log.i(TAG, "fast-path sent '${cmd.contactName}' cmd=${cmd.commandId}")
            notifyCommandSent(cmd)
            return
        }

        // 2. Full navigation path.
        if (!ensureWhatsAppForeground()) {
            notifyCommandFailed(cmd, "cannot open WhatsApp")
            return
        }
        delay(1_200)

        // Back out to chat list (noop if already there).
        navigateToMainScreen()
        delay(400)

        if (!openChatByName(cmd.contactName)) {
            Log.w(TAG, "chat not found: '${cmd.contactName}'")
            notifyCommandFailed(cmd, "chat not found")
            return
        }
        delay(900)

        // Verify the chat we opened actually matches the target.
        val root = rootInActiveWindow
        val opened = root?.let { extractCurrentChatName(it) }?.trim() ?: ""
        root?.recycle()
        if (opened.isBlank() || !namesMatch(opened, cmd.contactName)) {
            Log.w(TAG, "opened wrong chat: '$opened' ≠ '${cmd.contactName}'")
            notifyCommandFailed(cmd, "wrong chat opened")
            return
        }

        if (!setInputText(cmd.content)) {
            notifyCommandFailed(cmd, "input field not found")
            return
        }
        delay(250)

        val sent = attemptClickSend(maxAttempts = 12, delayMs = 250)
        if (sent) {
            Log.i(TAG, "sent '${cmd.contactName}' cmd=${cmd.commandId}")
            notifyCommandSent(cmd)
        } else {
            Log.w(TAG, "send button not found for cmd ${cmd.commandId}")
            notifyCommandFailed(cmd, "send button not found")
        }
    }

    /**
     * If WA is foreground AND on the correct chat, set input text and click
     * send. Returns true on success.
     */
    private suspend fun typeAndSendIntoOpenChat(cmd: DeviceCommand): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (pkg !in WA_PACKAGES) { root.recycle(); return false }

        val currentChatName = extractCurrentChatName(root)
        if (currentChatName == null) { root.recycle(); return false }
        if (!namesMatch(currentChatName, cmd.contactName)) {
            Log.d(TAG, "fast-path: wrong chat '$currentChatName' ≠ '${cmd.contactName}'")
            root.recycle()
            return false
        }

        val inputNodes = root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD)
            .ifEmpty { root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD_B) }
        val input = inputNodes.firstOrNull()
        if (input == null) { root.recycle(); return false }

        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                cmd.content,
            )
        }
        val textOk = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        root.recycle()
        if (!textOk) return false

        delay(250)
        return attemptClickSend(maxAttempts = 8, delayMs = 200)
    }

    private fun namesMatch(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        return x.equals(y, ignoreCase = true)
            || x.contains(y, ignoreCase = true)
            || y.contains(x, ignoreCase = true)
    }

    /**
     * Ensures WhatsApp is the foreground app. If it already is, returns true
     * immediately without launching it (preserving the current chat).
     */
    private fun ensureWhatsAppForeground(): Boolean {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        root?.recycle()
        if (pkg in WA_PACKAGES) return true

        val target = pickInstalledWaPackage() ?: return false
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(target)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        try {
            val wake = Intent(applicationContext, com.crm.whatsagent.WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.crm.whatsagent.WakeActivity.EXTRA_LAUNCH, launch)
            }
            applicationContext.startActivity(wake)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "cannot launch WhatsApp: ${e.message}")
            return false
        }
    }

    /**
     * Presses Back repeatedly until we reach the WhatsApp main screen
     * (chat list). This ensures we exit any open chat before searching.
     */
    private suspend fun navigateToMainScreen() {
        repeat(5) {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString() ?: ""
            if (pkg !in WA_PACKAGES) { root.recycle(); return }

            // If we see the search icon, we're on the main screen.
            val onMain = root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/menuitem_search").isNotEmpty()
                || root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_search").isNotEmpty()
                || findNodeByDesc(root, listOf("Search", "Поиск", "Іздеу")) != null

            root.recycle()
            if (onMain) {
                Log.d(TAG, "on WA main screen")
                return
            }

            Log.d(TAG, "pressing Back to reach main screen (attempt ${it + 1})")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(400)
        }
    }

    /**
     * Opens a chat by contact name using WhatsApp's search UI.
     * Tries multiple times because the search icon and result list need a moment to appear.
     */
    private suspend fun openChatByName(name: String): Boolean {
        // 1. Click the search action in the toolbar.
        if (!clickSearchIcon(maxAttempts = 8, delayMs = 250)) return false
        delay(400)

        // 2. Find the search EditText and type contact name.
        if (!typeIntoSearchField(name)) return false
        delay(700)  // wait for results

        // 3. Tap the first conversation row in results.
        return clickFirstChatResult(name, maxAttempts = 8, delayMs = 250)
    }

    private suspend fun clickSearchIcon(maxAttempts: Int, delayMs: Long): Boolean {
        val candidateIds = listOf(
            "com.whatsapp.w4b:id/menuitem_search",
            "com.whatsapp:id/menuitem_search",
        )
        repeat(maxAttempts) {
            val root = rootInActiveWindow
            if (root != null) {
                for (id in candidateIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    val node = nodes.firstOrNull()
                    if (node != null) {
                        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        root.recycle()
                        if (ok) return true
                    }
                }
                // Fallback: search by content description.
                val byDesc = findNodeByDesc(root, listOf("Search", "Поиск", "Іздеу"))
                if (byDesc != null) {
                    val ok = byDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    root.recycle()
                    if (ok) return true
                }
                root.recycle()
            }
            delay(delayMs)
        }
        return false
    }

    private fun typeIntoSearchField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val candidateIds = listOf(
                "com.whatsapp.w4b:id/search_src_text",
                "com.whatsapp:id/search_src_text",
            )
            for (id in candidateIds) {
                val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
                if (node != null) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            // Generic fallback: any focused EditText.
            val edit = findEditableNode(root)
            if (edit != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            return false
        } finally {
            root.recycle()
        }
    }

    private suspend fun clickFirstChatResult(name: String, maxAttempts: Int, delayMs: Long): Boolean {
        repeat(maxAttempts) {
            val root = rootInActiveWindow
            if (root != null) {
                // Look for a TextView matching the contact name (case-insensitive).
                val match = findNodeByText(root, name)
                if (match != null) {
                    // Click the row (climb up to a clickable ancestor).
                    var n: AccessibilityNodeInfo? = match
                    while (n != null && !n.isClickable) n = n.parent
                    val target = n ?: match
                    val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    root.recycle()
                    if (ok) return true
                }
                root.recycle()
            }
            delay(delayMs)
        }
        return false
    }

    private fun setInputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD)
                .ifEmpty { root.findAccessibilityNodeInfosByViewId(WA_INPUT_FIELD_B) }
            val input = nodes.firstOrNull() ?: findEditableNode(root) ?: return false
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        return matches.firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
            ?: matches.firstOrNull()
    }

    private fun findNodeByDesc(root: AccessibilityNodeInfo, descs: List<String>): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val d = n.contentDescription?.toString() ?: ""
            if (descs.any { d.contains(it, ignoreCase = true) } && n.isClickable) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun pickInstalledWaPackage(): String? {
        val pm = applicationContext.packageManager
        // Prefer WhatsApp Business (w4b) since the agent typically runs on a
        // dedicated business device. Fall back to standard WhatsApp.
        for (p in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            try {
                pm.getPackageInfo(p, 0)
                return p
            } catch (_: Exception) { /* not installed */ }
        }
        return null
    }

    private suspend fun attemptClickSend(maxAttempts: Int, delayMs: Long): Boolean {
        repeat(maxAttempts) {
            val root = rootInActiveWindow
            if (root != null) {
                val sendNodes = root.findAccessibilityNodeInfosByViewId(WA_SEND_BTN)
                    .ifEmpty { root.findAccessibilityNodeInfosByViewId(WA_SEND_BTN_B) }
                val sendBtn = sendNodes.firstOrNull()
                if (sendBtn != null && sendBtn.isEnabled) {
                    val result = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    root.recycle()
                    if (result) return true
                }
                root.recycle()
            }
            delay(delayMs)
        }
        return false
    }

    private fun notifyCommandSent(cmd: DeviceCommand) {
        // Track the sent text so we don't re-read it as an incoming message.
        recentlySent.add(cmd.content.trim())
        while (recentlySent.size > maxRecentlySent) recentlySent.poll()
        // Also update lastSeenContent to prevent immediate re-reading.
        lastSeenContent = cmd.content.trim()
        AgentForegroundService.instance?.notifyCommandStatus(cmd, "sent")
    }

    private fun notifyCommandFailed(cmd: DeviceCommand, reason: String) {
        AgentForegroundService.instance?.notifyCommandStatus(cmd, "failed", reason)
    }

    // ─── UI tree helpers ──────────────────────────────────────

    private fun getLastChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val count = node.childCount
        return if (count > 0) node.getChild(count - 1) else null
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectText(child))
        }
        return sb.toString()
    }

    /**
     * Outgoing bubbles in WhatsApp have a content description that starts with "You:" (English),
     * "Вы:" (Russian), or a specific view ID suffix "_out". Heuristic — may need adjustment per WA version / locale.
     */
    private fun isOutgoingBubble(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString() ?: ""
        // Check common locale prefixes for outgoing messages.
        val outPrefixes = listOf("You:", "Вы:", "Siz:", "Сіз:", "Tu:", "Usted:", "Vous:", "Sie:")
        if (outPrefixes.any { desc.startsWith(it, ignoreCase = true) }) return true
        val id = node.viewIdResourceName ?: ""
        if (id.contains("_out") || id.contains("outgoing")) return true
        // Fallback: check children recursively for outgoing indicators.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childDesc = child.contentDescription?.toString() ?: ""
            if (outPrefixes.any { childDesc.startsWith(it, ignoreCase = true) }) return true
            val childId = child.viewIdResourceName ?: ""
            if (childId.contains("_out") || childId.contains("outgoing")) return true
        }
        return false
    }

    private fun extractCurrentChatPhone(root: AccessibilityNodeInfo): String? {
        // WhatsApp sets the chat header title; from the ActionBar subtitle we may get the phone.
        // As a fallback we return empty; the backend will infer from contact name.
        val headerNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/conversation_contact_name") }
        val text = headerNodes.firstOrNull()?.text?.toString() ?: return null
        // If the header is a phone number (e.g., "+1 415 555 1234"), normalise it.
        return if (text.any { it.isDigit() }) PhoneNormalizer.normalize(text) else null
    }

    private fun extractCurrentChatName(root: AccessibilityNodeInfo): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/conversation_contact_name") }
        return nodes.firstOrNull()?.text?.toString()
    }

    // ─── SEND via notification shade reply ────────────────────

    /**
     * Opens the notification shade, finds the WhatsApp notification for the target
     * contact, clicks the inline Reply button, types the message and sends.
     * This avoids opening WhatsApp at all — the reply goes through the notification
     * RemoteInput, which is silent and always routes to the correct chat.
     *
     * Returns true if the message was typed and sent successfully.
     */
    suspend fun sendViaNotificationReply(cmd: DeviceCommand): Boolean {
        Log.i(TAG, "trying notification shade reply for '${cmd.contactName}'")

        // 1. Pull down the notification shade.
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        delay(2000)

        // Debug: check rootInActiveWindow
        val activeRoot = rootInActiveWindow
        Log.d(TAG, "rootInActiveWindow: pkg=${activeRoot?.packageName} cls=${activeRoot?.className} children=${activeRoot?.childCount}")
        if (activeRoot != null) {
            dumpNodeTree(activeRoot, "  active> ", 0, maxDepth = 5)
            activeRoot.recycle()
        }

        // 2. Find all windows — the notification shade is a SystemUI window.
        val allWindows = try { windows } catch (_: Exception) {
            Log.w(TAG, "cannot get windows list")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return false
        }

        Log.d(TAG, "windows count: ${allWindows?.size ?: 0}")
        allWindows?.forEachIndexed { idx, w ->
            val root = w.root
            val pkg = root?.packageName ?: "null"
            val cls = root?.className ?: "null"
            val cc = root?.childCount ?: 0
            Log.d(TAG, "  window[$idx] type=${w.type} pkg=$pkg cls=$cls children=$cc")
            root?.recycle()
        }

        // 3. Search across all windows for a WA Business notification matching the contact.
        var replyNode: AccessibilityNodeInfo? = null
        var notifRoot: AccessibilityNodeInfo? = null
        for (w in allWindows ?: emptyList()) {
            val root = w.root ?: continue
            replyNode = findNotificationReplyButton(root, cmd.contactName)
            if (replyNode != null) {
                notifRoot = root
                break
            }
            root.recycle()
        }

        if (replyNode == null) {
            Log.w(TAG, "notification reply button not found for '${cmd.contactName}'")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return false
        }

        // 4. Click the Reply button to open inline reply field.
        replyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(600)

        // 5. Find the inline reply EditText and type the message.
        // After clicking Reply, Samsung shows an EditText in the notification.
        val typed = typeIntoNotificationReply(cmd.content)
        if (!typed) {
            Log.w(TAG, "could not type into notification reply field")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return false
        }
        delay(300)

        // 6. Click the send button in the inline reply.
        val sent = clickNotificationSendButton()
        if (!sent) {
            Log.w(TAG, "notification send button not found")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return false
        }

        // 7. Close the notification shade.
        delay(500)
        performGlobalAction(GLOBAL_ACTION_BACK)

        Log.i(TAG, "notification shade reply sent to '${cmd.contactName}'")
        return true
    }

    /**
     * Walks the notification shade UI tree looking for a WA Business notification
     * that mentions the target contact name, then finds its Reply/Ответить button.
     */
    private fun findNotificationReplyButton(root: AccessibilityNodeInfo, contactName: String): AccessibilityNodeInfo? {
        // Debug: dump first 2 levels of the tree to see what's there
        dumpNodeTree(root, "  ", 0, maxDepth = 5)

        // Strategy: find a node whose text contains the contact name,
        // then look for a sibling/nearby node that's the Reply button.
        val contactNode = findNodeContainingText(root, contactName) ?: return null
        Log.d(TAG, "found notification for '$contactName'")

        // Walk up to the notification container, then search for Reply button.
        var container: AccessibilityNodeInfo? = contactNode
        for (i in 0..5) {
            container = container?.parent ?: break
        }
        if (container == null) return null

        // Look for Reply/Ответить/Жауап button within this container.
        return findReplyButtonInTree(container)
    }

    private fun findNodeContainingText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val textLower = text.lowercase()
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (nodeText.contains(textLower) || nodeDesc.contains(textLower)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun findReplyButtonInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val replyLabels = listOf("reply", "ответить", "жауап беру", "жауап")
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isClickable && replyLabels.any { text.contains(it) || desc.contains(it) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    /**
     * After clicking Reply in the notification, find the inline EditText
     * and set the message text.
     */
    private fun typeIntoNotificationReply(text: String): Boolean {
        // The inline reply field appears in the notification shade after clicking Reply.
        // Search all windows for a focused/editable EditText.
        val allWindows = try { windows } catch (_: Exception) { return false }
        for (w in allWindows) {
            val root = w.root ?: continue
            val editNode = findEditableNode(root)
            if (editNode != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                val ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                root.recycle()
                if (ok) {
                    Log.d(TAG, "typed into notification reply field")
                    return true
                }
            }
            root.recycle()
        }
        return false
    }

    /**
     * After typing into the notification inline reply, find and click the Send button.
     * Samsung One UI shows a send arrow icon; its content description varies by locale.
     */
    private fun clickNotificationSendButton(): Boolean {
        val sendLabels = listOf("send", "отправить", "жіберу", "жіб")
        val allWindows = try { windows } catch (_: Exception) { return false }
        for (w in allWindows) {
            val root = w.root ?: continue
            val sendBtn = findSendButtonInTree(root, sendLabels)
            if (sendBtn != null) {
                val ok = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                root.recycle()
                if (ok) {
                    Log.d(TAG, "clicked notification send button")
                    return true
                }
            }
            root.recycle()
        }
        return false
    }

    private fun findSendButtonInTree(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            // Match by text/description containing send labels, or by ImageButton class.
            if (node.isClickable && labels.any { desc.contains(it) || text.contains(it) }) return node
            // Also check for send-arrow ImageButton (no text, but clickable, class=ImageButton).
            val cls = node.className?.toString() ?: ""
            if (node.isClickable && cls.contains("ImageButton") && desc.isBlank() && text.isBlank()) {
                // Heuristic: the send button in notification reply is usually the only ImageButton.
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun dumpNodeTree(node: AccessibilityNodeInfo, prefix: String, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val text = node.text?.toString()?.take(60) ?: ""
        val desc = node.contentDescription?.toString()?.take(60) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val click = if (node.isClickable) " [CLICK]" else ""
        val vid = node.viewIdResourceName ?: ""
        Log.d(TAG, "${prefix}$cls text='$text' desc='$desc' id='$vid'$click children=${node.childCount}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, "$prefix  ", depth + 1, maxDepth)
        }
    }
}
