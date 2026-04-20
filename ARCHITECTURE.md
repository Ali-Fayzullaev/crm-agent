# CRM × WhatsApp Integration — Architecture

## Stack

| Layer | Technology | Reason |
|---|---|---|
| Android Agent | **Kotlin** + OkHttp | Native Android, coroutines, best WS support |
| Backend API | **Go** (Chi + pgx + gorilla/ws) | High concurrency, single binary, low memory |
| Message Queue | **Redis Streams** | Persistent, ordered, consumer-groups, simple ops |
| Database | **PostgreSQL 16** | ACID, JSONB, rich indexing |
| Infra | **Docker Compose** → K8s | Dev → production path |

---

## 1. Architecture Diagram (text)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Android Device                               │
│                                                                     │
│  ┌──────────────┐   notification   ┌──────────────────────────────┐ │
│  │  WhatsApp    │ ───────────────► │  NotificationListenerService │ │
│  │  (unchanged) │                  └──────────┬───────────────────┘ │
│  │              │   UI tree read   ┌──────────▼───────────────────┐ │
│  │              │ ◄──────────────► │  AccessibilityService        │ │
│  │              │   UI automation  │  (read foreground msgs +     │ │
│  └──────────────┘                  │   click Send button)         │ │
│          ▲                         └──────────┬───────────────────┘ │
│          │ startActivity(deeplink)            │ events              │
│          │                         ┌──────────▼───────────────────┐ │
│          └─────────────────────────│  AgentForegroundService      │ │
│                                    │  ┌──────────────────────┐    │ │
│                                    │  │ LocalMessageQueue    │    │ │
│                                    │  │ (SQLite — offline)   │    │ │
│                                    │  └──────────────────────┘    │ │
│                                    │  ┌──────────────────────┐    │ │
│                                    │  │ AgentWebSocketClient │    │ │
│                                    │  │ (OkHttp, auto-retry) │    │ │
│                                    └──────────┬───────────────────┘ │
└───────────────────────────────────────────────┼─────────────────────┘
                                                │ WSS /ws/agent
                              ┌─────────────────▼─────────────────────┐
                              │           Go Backend                  │
                              │                                       │
                              │  ┌────────────┐  ┌────────────────┐  │
                              │  │ Hub        │  │ MessageService │  │
                              │  │ (agent map │  │ (ingestion +   │  │
                              │  │  operator  │  │  dedup +       │  │
                              │  │  map)      │  │  SendReply)    │  │
                              │  └──────┬─────┘  └──────┬─────────┘  │
                              │         │               │             │
                              │  ┌──────▼───────────────▼──────────┐ │
                              │  │  Redis Streams (per-device)     │ │
                              │  │  commands:{device_id}           │ │
                              │  └──────────────────────────────────┘ │
                              │  ┌──────────────────────────────────┐ │
                              │  │  PostgreSQL                      │ │
                              │  │  devices / contacts /            │ │
                              │  │  conversations / messages /      │ │
                              │  │  device_commands                 │ │
                              │  └──────────────────────────────────┘ │
                              └────────────────┬──────────────────────┘
                                               │ WSS /ws/crm + REST
                              ┌────────────────▼──────────────────────┐
                              │          CRM Web UI                   │
                              │  Conversation list | Chat window      │
                              │  Operator reply  → POST /reply        │
                              └────────────────────────────────────────┘
```

---

## 2. Android Agent Architecture

### Services

| Service | Role | Lifecycle |
|---|---|---|
| `AgentForegroundService` | Core orchestrator, WS client, queue flush | `START_STICKY`, foreground notification |
| `WhatsAppNotificationListener` | Intercepts incoming WA notifications | System-managed (exempt from battery kill) |
| `WhatsAppAccessibilityService` | Reads UI when WA is foreground; clicks Send | System-managed |
| `BootReceiver` | Restarts service after boot/update | BroadcastReceiver |

### Message capture strategy

```
Incoming message
  └─► WA shows notification
        ├─► NotificationListenerService.onNotificationPosted()
        │     extracts: EXTRA_TITLE (name), EXTRA_MESSAGES (MessagingStyle)
        │     → enqueueEvent()
        └─► (if WA is foreground, no notification)
              AccessibilityService.TYPE_WINDOW_CONTENT_CHANGED
              → reads last message bubble from UI tree
              → enqueueEvent()

Outgoing message (user-initiated)
  └─► User taps Send in WhatsApp
        └─► TYPE_VIEW_CLICKED on send button (viewId = com.whatsapp:id/send)
              → read text from input field before it clears
              → enqueueEvent(direction=OUT)
```

### Sending replies (Backend → WhatsApp)

```
Backend sends command via WebSocket
  └─► AgentForegroundService.handleIncomingCommand()
        └─► WhatsAppAccessibilityService.enqueueSendCommand()
              └─► openWhatsAppForCommand()
                    Intent(whatsapp://send?phone=+X&text=MSG) → startActivity()
              └─► processSendQueue()
                    poll UI tree every 300 ms (max 8 attempts)
                    find com.whatsapp:id/send → performAction(ACTION_CLICK)
                    → notifyCommandStatus("sent")
```

### Offline / Reliability

```
MessageEvent captured
  └─► LocalMessageQueue.enqueue() [SQLite, idempotency_key UNIQUE]
        └─► flushQueue() if WS connected
              ├─► wsClient.sendEvent() → ack() delete from queue
              └─► (failure) incrementRetry() → retry on next flush
                    max_retries = 10 → drop after 10 failures

WS reconnect → onConnected() → flushQueue() drains pending SQLite rows
```

### Android 10–14 restrictions

| Version | Restriction | Mitigation |
|---|---|---|
| Android 10 | Background location | Not needed |
| Android 10 | Background Activity Start | Use `FLAG_ACTIVITY_NEW_TASK` for deeplinks ✓ |
| Android 11 | Package visibility | `<queries>` block in manifest for com.whatsapp ✓ |
| Android 12 | PendingIntent mutability | `FLAG_IMMUTABLE` on all PendingIntents ✓ |
| Android 12 | Exact alarms restricted | Use WorkManager instead ✓ |
| Android 13 | `POST_NOTIFICATIONS` runtime | Requested in MainActivity ✓ |
| Android 14 | `foregroundServiceType` | Declared `connectedDevice` in manifest ✓ |
| All | Battery optimisation | Request `IGNORE_BATTERY_OPTIMIZATIONS` ✓ |

---

## 3. Backend Architecture

```
HTTP / WS (port 8080)
  │
  ├── Chi Router
  │     ├── POST /api/v1/auth/login        → JWT for operators
  │     ├── POST /api/v1/devices/register  → device JWT
  │     ├── GET  /ws/agent                 → AgentClient (device WS)
  │     ├── GET  /ws/crm                   → OperatorClient (CRM WS)
  │     └── /api/v1/conversations/*        → REST CRUD
  │
  ├── Hub (goroutine)
  │     ├── agents    map[device_id]*AgentClient
  │     ├── operators map[operator_id]*OperatorClient
  │     └── broadcast channel → fan-out to all operators
  │
  ├── MessageService
  │     ├── ingestMessage()   → upsert contact/conv → insert msg (idempotent)
  │     │                        → broadcast to operators
  │     └── SendReply()       → insert msg → send WS direct OR Redis Stream
  │
  ├── Repository (pgx pool, 30 conns)
  │     ├── UpsertContact / UpsertConversation (ON CONFLICT DO NOTHING/UPDATE)
  │     ├── InsertMessage (ON CONFLICT idempotency_key DO NOTHING)
  │     └── CreateCommand / UpdateCommandStatus
  │
  └── RedisStreams
        ├── PublishCommand(device_id, cmd)
        └── StartConsumer(device_id) → XReadGroup → SendToDevice
```

### Scaling to 1000+ devices

- **Stateless backend**: multiple instances behind a load balancer. Hub state is per-instance. Sticky sessions (by device_id) on WS upgrade keep the right Hub.
- **Redis Streams** fan-out: each backend instance subscribes to the streams whose devices are connected to it. When a device migrates, the consumer group handles it.
- **PostgreSQL connection pooling**: 30 pgx connections per instance; use PgBouncer in front at scale.
- **Horizontal scaling**: Redis Pub/Sub can be added to broadcast events across Hub instances when needed.

---

## 4. Data Model

```
devices
  id UUID PK
  name VARCHAR
  token VARCHAR UNIQUE (JWT)
  phone_number VARCHAR
  status VARCHAR (online|offline)
  last_seen_at TIMESTAMPTZ
  created_at, updated_at

contacts
  id UUID PK
  device_id UUID FK→devices
  phone_number VARCHAR
  name VARCHAR
  UNIQUE(device_id, phone_number)

conversations
  id UUID PK
  device_id UUID FK→devices
  contact_id UUID FK→contacts
  assigned_operator_id UUID FK→operators (nullable)
  status VARCHAR (open|closed|archived)
  unread_count INT
  last_message_at TIMESTAMPTZ
  last_message_text TEXT
  UNIQUE(device_id, contact_id)

messages
  id UUID PK
  conversation_id UUID FK→conversations
  device_id UUID FK→devices
  external_id VARCHAR (WhatsApp internal ID)
  direction VARCHAR (in|out)
  content TEXT
  content_type VARCHAR (text|image|audio)
  status VARCHAR (received|queued|sent|delivered|read|failed)
  idempotency_key VARCHAR UNIQUE  ← SHA-256 dedup key
  operator_id UUID FK→operators (nullable, who typed the reply)
  created_at, sent_at, delivered_at, read_at

operators
  id UUID PK
  email VARCHAR UNIQUE
  name VARCHAR
  password_hash VARCHAR
  role VARCHAR (agent|admin)

device_commands
  id UUID PK
  device_id UUID FK→devices
  conversation_id UUID FK→conversations (nullable)
  message_id UUID FK→messages (nullable)
  command_type VARCHAR (send_message)
  payload JSONB
  status VARCHAR (pending|delivered|completed|failed)
  retry_count INT, max_retries INT
  created_at, delivered_at, processed_at
```

---

## 5. API + WebSocket Schema

### REST

```
POST /api/v1/auth/login
Body: { "email": "...", "password": "..." }
Response: { "token": "JWT...", "operator": { ... } }

POST /api/v1/devices/register
Body: { "name": "Device-01", "phone_number": "+79001234567" }
Response: { "device": { ... }, "token": "JWT..." }

GET /api/v1/conversations?status=open&limit=50&offset=0
Response: { "conversations": [ ... ] }

GET /api/v1/conversations/:id/messages?limit=100&offset=0
Response: { "messages": [ ... ] }

POST /api/v1/conversations/:id/reply
Body: { "content": "Hello!" }
Response: { "message": { ... } }

PATCH /api/v1/conversations/:id
Body: { "status": "closed" } OR { "assigned_operator_id": "UUID" }
Response: { "ok": true }
```

### WebSocket: Device Agent → Backend

```jsonc
// New incoming WhatsApp message
{
  "type": "message_received",
  "payload": {
    "idempotency_key": "a3f8c...",
    "contact_name":    "John Doe",
    "contact_phone":   "+79001234567",
    "content":         "Hi, is the order ready?",
    "content_type":    "text",
    "direction":       "in",
    "timestamp":       "2026-04-17T10:32:00Z"
  }
}

// Delivery / send acknowledgement
{
  "type": "status_update",
  "payload": {
    "command_id":      "b9e2f...",
    "idempotency_key": "a3f8c...",
    "status":          "sent"
  }
}

// Heartbeat (every 30 s)
{ "type": "heartbeat", "payload": {} }
```

### WebSocket: Backend → Device Agent

```jsonc
// Command to send a WhatsApp message
{
  "type": "send_message",
  "payload": {
    "command_id":      "b9e2f...",
    "contact_phone":   "+79001234567",
    "content":         "Yes, your order is ready!",
    "conversation_id": "uuid..."
  }
}
```

### WebSocket: Backend → CRM Operator

```jsonc
// New message in any conversation
{
  "type": "new_message",
  "payload": {
    "conversation_id": "uuid...",
    "message": {
      "id": "uuid...",
      "direction": "in",
      "content": "Hi, is the order ready?",
      "created_at": "2026-04-17T10:32:00Z",
      ...
    }
  }
}

// Conversation updated (status change, assignment)
{ "type": "conversation_updated", "payload": { "conversation": { ... } } }

// Device came online / went offline
{ "type": "device_status", "payload": { "device_id": "...", "status": "online" } }

// Command executed on device
{ "type": "command_ack", "payload": { "command_id": "...", "status": "sent" } }
```

---

## 6. Message Lifecycle (Step by Step)

```
1.  Client sends "Hi!" in WhatsApp on the Android device.

2.  WhatsApp posts a notification (or updates the UI if it's in foreground).

3a. NotificationListenerService.onNotificationPosted() extracts:
      contactName = "John Doe"
      content     = "Hi!"
      timestamp   = now()
    OR
3b. AccessibilityService reads the last bubble from the conversation UI tree.

4.  AgentForegroundService.enqueueEvent():
      • Compute idempotencyKey = SHA-256(deviceId, phone, content, ts)
      • LocalMessageQueue.enqueue() → INSERT OR IGNORE into SQLite

5.  flushQueue() → AgentWebSocketClient.sendEvent()
      → WebSocket TEXT frame:  { "type": "message_received", "payload": { ... } }

6.  Backend Hub.OnAgentMessage() → MessageService.ingestMessage():
      • UpsertContact (INSERT OR IGNORE)
      • UpsertConversation (INSERT OR IGNORE)
      • InsertMessage with ON CONFLICT idempotency_key DO NOTHING
                                       ↑ dedup gate
      • UpdateConversationLastMessage()
      • hub.BroadcastJSON(new_message)

7.  All connected CRM operators receive WebSocket frame:
      { "type": "new_message", "payload": { "message": { ... } } }
    The web UI renders the message in the conversation view.

8.  Operator types "Yes, ready!" and clicks Send in the web UI.

9.  Browser → POST /api/v1/conversations/:id/reply { "content": "Yes, ready!" }

10. MessageService.SendReply():
      • InsertMessage (direction=out, status=queued)
      • CreateCommand (device_commands record)
      • hub.SendToDevice() — if device WS is open: direct delivery
      • OR PublishCommand to Redis Stream — if device is offline

11. Device receives WebSocket frame:
      { "type": "send_message", "payload": { "command_id": "...", "contact_phone": "...", ... } }
    OR Redis Stream consumer delivers when device reconnects.

12. AgentForegroundService.handleIncomingCommand()
      → WhatsAppAccessibilityService.enqueueSendCommand()
      → openWhatsAppForCommand():
          startActivity(whatsapp://send?phone=+X&text=Yes%2C+ready%21)

13. WhatsApp opens in the target contact's chat with text pre-filled.

14. AccessibilityService.processSendQueue():
      polls for com.whatsapp:id/send (max 8 × 300 ms)
      → sendBtn.performAction(ACTION_CLICK) ✓

15. Agent sends: { "type": "status_update", "payload": { "command_id": "...", "status": "sent" } }

16. Backend updates: messages.status = 'sent', device_commands.status = 'completed'
    broadcasts  { "type": "command_ack", ... } to all operators.
```

---

## 7. Failure Scenarios

| Scenario | Behaviour |
|---|---|
| **Device offline / no internet** | Messages accumulate in SQLite `pending_events`. On reconnect, `flushQueue()` replays them in order. |
| **Backend restart** | Device WebSocket reconnects with exponential backoff (2 s → 4 → 8 → … → 60 s). Redis Stream retains pending commands; consumer group replays. |
| **Duplicate message** | `idempotency_key` UNIQUE constraint → `INSERT … ON CONFLICT DO NOTHING`. Duplicate silently dropped on both device (SQLite) and backend (PostgreSQL). |
| **Send command lost (device went offline after command sent)** | Command stays in `device_commands` with status=pending. Redis Stream entry unacked. When device reconnects, `StartConsumer()` calls `XAutoClaim` → reclaims idle entries → retries. |
| **MaxRetries exceeded** | `device_commands.status = 'failed'`, operator sees error in UI via `command_ack(failed)`. |
| **AccessibilityService not enabled** | `enqueueSendCommand()` logs error, sends `status_update(failed)` to backend immediately. Operator notified. |
| **WhatsApp update breaks resource IDs** | `NotificationListenerService` path continues working. Send path fails with "send button not found" after 8 retries → manual investigation needed. |
| **PostgreSQL down** | Backend returns 503. Devices buffer locally. WS Hub still runs for delivery if data is cached. |

---

## 8. Trade-offs

### NotificationListenerService vs AccessibilityService

| | NLS | A11y |
|---|---|---|
| **Setup** | Settings one-tap | Settings accessibility menu |
| **Reliability** | Very high (system-level) | High but more fragile |
| **Coverage** | Incoming only; misses foreground | Both in/out, all states |
| **Message length** | Truncated ~100 chars | Full text from UI |
| **WhatsApp update risk** | Low (notification API stable) | Medium (IDs can change) |
| **Battery** | Minimal | Slightly higher (UI tree parsing) |
| **Our choice** | Primary for incoming | Backup for foreground + all outgoing |

### Latency vs Battery

| Approach | Latency | Battery Impact |
|---|---|---|
| Persistent WS + ping 30 s | ~50–200 ms | Moderate (keep-alive) |
| Long-poll HTTP | ~1–3 s | Lower (no keep-alive) |
| WebSocket + Doze-aware | ~200 ms–30 s in deep Doze | Lower |
| Firebase Cloud Messaging (wake) | Variable + FCM dependency | Lowest |

**Decision**: Persistent WebSocket with 30 s ping. Best real-time UX for CRM. Doze mode interruptions acceptable (max 30 s delay).

### Risk of message loss

| Risk | Mitigation |
|---|---|
| NLS misses a message (WA foreground) | AccessibilityService as secondary reader |
| A11y misses message (busy UI) | NLS is primary for notifications |
| WS drops during send | SQLite queue + resend on reconnect |
| Backend crashes mid-insert | PostgreSQL WAL + pgx retry on transient errors |
| Redis OOM | `maxmemory-policy noeviction` + stream `MAXLEN` 10000 |

---

## 9. Final Recommendation

### Use this stack:

| Component | Choice | Why |
|---|---|---|
| Android capture | NLS (primary) + A11y (secondary) | Maximum coverage without WA modification |
| Android send | A11y deeplink + UI click | Most reliable OS-level approach |
| Android resilience | SQLite queue + START_STICKY + BootReceiver | Survives Doze, reboot, process kill |
| Transport | WebSocket (OkHttp + gorilla/ws) | Real-time, ~50 ms latency |
| Offline buffer | Redis Streams (consumer groups) | Durable, ordered, replayable |
| Backend runtime | Go | 50 k concurrent WS connections per instance |
| Database | PostgreSQL | Best ACID + ON CONFLICT for idempotency |

### What to build next (roadmap)

1. **Image / voice message support** — A11y can detect media bubbles; download via WhatsApp share intent
2. **Read receipt sync** — A11y `TYPE_WINDOW_CONTENT_CHANGED` on "double blue tick" nodes
3. **Multi-device support** — Multiple phones for the same CRM tenant
4. **Metrics** — Prometheus `/metrics` endpoint on backend (connected devices, queue depth, ingestion rate)
5. **mTLS** between device and backend — replace shared JWT secret with per-device client certs

---

## Quick Start

```bash
# 1. Start backend + DB + Redis
docker compose up --build

# 2. Register a device (get the JWT token)
curl -X POST http://localhost:8080/api/v1/devices/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Phone-01","phone_number":"+79001234567"}'

# 3. Build Android APK
cd android-agent
# Set DEVICE_TOKEN and BACKEND_WS_URL in local.properties
./gradlew assembleRelease

# 4. Install APK on device, grant permissions in Settings
# 5. Open CRM web app at http://localhost:3000 (your frontend)
```
