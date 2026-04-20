package models

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// ─── Domain entities ──────────────────────────────────────────

type Device struct {
	ID          uuid.UUID  `json:"id"           db:"id"`
	Name        string     `json:"name"         db:"name"`
	Token       string     `json:"-"            db:"token"`
	PhoneNumber string     `json:"phone_number" db:"phone_number"`
	Status      string     `json:"status"       db:"status"` // online | offline
	LastSeenAt  *time.Time `json:"last_seen_at" db:"last_seen_at"`
	CreatedAt   time.Time  `json:"created_at"   db:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"   db:"updated_at"`
}

type Contact struct {
	ID          uuid.UUID `json:"id"           db:"id"`
	DeviceID    uuid.UUID `json:"device_id"    db:"device_id"`
	PhoneNumber string    `json:"phone_number" db:"phone_number"`
	Name        string    `json:"name"         db:"name"`
	CreatedAt   time.Time `json:"created_at"   db:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"   db:"updated_at"`
}

type Conversation struct {
	ID                 uuid.UUID  `json:"id"                   db:"id"`
	DeviceID           uuid.UUID  `json:"device_id"            db:"device_id"`
	ContactID          uuid.UUID  `json:"contact_id"           db:"contact_id"`
	AssignedOperatorID *uuid.UUID `json:"assigned_operator_id" db:"assigned_operator_id"`
	Status             string     `json:"status"               db:"status"` // open | closed | archived
	UnreadCount        int        `json:"unread_count"         db:"unread_count"`
	LastMessageAt      *time.Time `json:"last_message_at"      db:"last_message_at"`
	LastMessageText    string     `json:"last_message_text"    db:"last_message_text"`
	CreatedAt          time.Time  `json:"created_at"           db:"created_at"`
	UpdatedAt          time.Time  `json:"updated_at"           db:"updated_at"`

	// Joined for list views
	ContactName  string `json:"contact_name,omitempty"  db:"contact_name"`
	ContactPhone string `json:"contact_phone,omitempty" db:"contact_phone"`
	DeviceName   string `json:"device_name,omitempty"   db:"device_name"`
}

type Message struct {
	ID             uuid.UUID  `json:"id"              db:"id"`
	ConversationID uuid.UUID  `json:"conversation_id" db:"conversation_id"`
	DeviceID       uuid.UUID  `json:"device_id"       db:"device_id"`
	ExternalID     string     `json:"external_id"     db:"external_id"`
	Direction      string     `json:"direction"       db:"direction"` // in | out
	Content        string     `json:"content"         db:"content"`
	ContentType    string     `json:"content_type"    db:"content_type"` // text | image | audio
	Status         string     `json:"status"          db:"status"`
	IdempotencyKey string     `json:"idempotency_key" db:"idempotency_key"`
	ErrorMessage   string     `json:"error_message"   db:"error_message"`
	OperatorID     *uuid.UUID `json:"operator_id"     db:"operator_id"`
	CreatedAt      time.Time  `json:"created_at"      db:"created_at"`
	SentAt         *time.Time `json:"sent_at"         db:"sent_at"`
	DeliveredAt    *time.Time `json:"delivered_at"    db:"delivered_at"`
	ReadAt         *time.Time `json:"read_at"         db:"read_at"`
}

type Operator struct {
	ID           uuid.UUID `json:"id"         db:"id"`
	Email        string    `json:"email"      db:"email"`
	Name         string    `json:"name"       db:"name"`
	PasswordHash string    `json:"-"          db:"password_hash"`
	Role         string    `json:"role"       db:"role"`
	CreatedAt    time.Time `json:"created_at" db:"created_at"`
}

type DeviceCommand struct {
	ID             uuid.UUID  `json:"id"              db:"id"`
	DeviceID       uuid.UUID  `json:"device_id"       db:"device_id"`
	ConversationID *uuid.UUID `json:"conversation_id" db:"conversation_id"`
	MessageID      *uuid.UUID `json:"message_id"      db:"message_id"`
	CommandType    string     `json:"command_type"    db:"command_type"`
	Payload        []byte     `json:"payload"         db:"payload"`
	Status         string     `json:"status"          db:"status"`
	RetryCount     int        `json:"retry_count"     db:"retry_count"`
	MaxRetries     int        `json:"max_retries"     db:"max_retries"`
	ErrorMessage   string     `json:"error_message"   db:"error_message"`
	CreatedAt      time.Time  `json:"created_at"      db:"created_at"`
	DeliveredAt    *time.Time `json:"delivered_at"    db:"delivered_at"`
	ProcessedAt    *time.Time `json:"processed_at"    db:"processed_at"`
}

// ─── WebSocket envelope ────────────────────────────────────────

const (
	// Agent → Backend
	EventTypeMessageReceived = "message_received" // incoming WhatsApp msg
	EventTypeMessageSent     = "message_sent"     // agent confirmed it sent
	EventTypeStatusUpdate    = "status_update"    // delivery/read receipt
	EventTypeHeartbeat       = "heartbeat"

	// Backend → Agent
	CmdTypeSendMessage = "send_message"

	// Backend → CRM Operator
	EventTypeNewMessage      = "new_message"
	EventTypeConversationUpd = "conversation_updated"
	EventTypeDeviceStatus    = "device_status"
	EventTypeCommandAck      = "command_ack"
)

// WSEnvelope is the top-level WebSocket frame.
type WSEnvelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

// ─── Agent → Backend payloads ─────────────────────────────────

// MessageReceivedPayload is sent by the Android agent when a new WhatsApp
// message appears (direction = "in") or when the agent sends one (direction = "out").
type MessageReceivedPayload struct {
	IdempotencyKey string `json:"idempotency_key"` // SHA-256(device_id+phone+ts+content)
	ExternalID     string `json:"external_id"`     // WhatsApp internal ID, if available
	ContactName    string `json:"contact_name"`
	ContactPhone   string `json:"contact_phone"` // E.164 format: +79001234567
	Direction      string `json:"direction"`     // "in" | "out"
	Content        string `json:"content"`
	ContentType    string `json:"content_type"` // "text"
	Timestamp      string `json:"timestamp"`    // RFC3339
}

// StatusUpdatePayload reports delivery / read receipts or command results.
type StatusUpdatePayload struct {
	CommandID      string `json:"command_id,omitempty"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
	Status         string `json:"status"`        // sent | delivered | read | failed
	ErrorMessage   string `json:"error,omitempty"`
}

// ─── Backend → Agent payloads ─────────────────────────────────

// SendMessageCmd instructs the agent to send a WhatsApp message.
type SendMessageCmd struct {
	CommandID    string `json:"command_id"`
	ContactPhone string `json:"contact_phone"`
	ContactName  string `json:"contact_name"`
	Content      string `json:"content"`
	ConvID       string `json:"conversation_id"`
}

// ─── Backend → CRM Operator payloads ─────────────────────────

type NewMessagePayload struct {
	ConversationID string  `json:"conversation_id"`
	Message        Message `json:"message"`
}

type ConvUpdatedPayload struct {
	Conversation Conversation `json:"conversation"`
}

type DeviceStatusPayload struct {
	DeviceID string `json:"device_id"`
	Status   string `json:"status"` // online | offline
}
