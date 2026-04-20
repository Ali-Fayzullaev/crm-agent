// Package service contains the core business logic.
package service

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/crm/backend/internal/models"
	"github.com/crm/backend/internal/queue"
	"github.com/crm/backend/internal/repository"
	"github.com/crm/backend/internal/ws"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

// MessageService orchestrates message ingestion and delivery.
type MessageService struct {
	repo   *repository.Repository
	hub    *ws.Hub
	queue  *queue.RedisStreams
	log    *zap.Logger
}

func New(repo *repository.Repository, hub *ws.Hub, q *queue.RedisStreams, log *zap.Logger) *MessageService {
	svc := &MessageService{repo: repo, hub: hub, queue: q, log: log}
	// Wire the hub's agent-message callback to this service.
	hub.OnAgentMessage = svc.HandleAgentMessage
	return svc
}

// ─── Ingestion ────────────────────────────────────────────────

// HandleAgentMessage is called by the Hub for every raw message from a device.
// It parses the envelope, routes to the correct handler.
func (s *MessageService) HandleAgentMessage(deviceID string, raw []byte) {
	var env models.WSEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		s.log.Warn("invalid agent envelope", zap.String("device", deviceID), zap.Error(err))
		return
	}

	ctx := context.Background()
	switch env.Type {
	case models.EventTypeMessageReceived:
		var p models.MessageReceivedPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			s.log.Warn("bad message_received payload", zap.Error(err))
			return
		}
		if err := s.ingestMessage(ctx, deviceID, p); err != nil {
			s.log.Error("ingest message", zap.Error(err))
		}

	case models.EventTypeStatusUpdate:
		var p models.StatusUpdatePayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			s.log.Warn("bad status_update payload", zap.Error(err))
			return
		}
		s.handleStatusUpdate(ctx, deviceID, p)

	case models.EventTypeHeartbeat:
		devID, _ := uuid.Parse(deviceID)
		_ = s.repo.SetDeviceStatus(ctx, devID, "online")

	default:
		s.log.Warn("unknown agent event type", zap.String("type", env.Type))
	}
}

// ingestMessage processes one incoming or outgoing WhatsApp message from an agent.
func (s *MessageService) ingestMessage(ctx context.Context, deviceID string, p models.MessageReceivedPayload) error {
	devID, err := uuid.Parse(deviceID)
	if err != nil {
		return fmt.Errorf("invalid device_id: %w", err)
	}

	// 1. Upsert contact.
	// If the agent didn't supply a phone number, use the contact name as a
	// fallback unique identifier so different senders don't collapse into one.
	phone := p.ContactPhone
	if phone == "" && p.ContactName != "" {
		// Use name as phone only if it actually looks like a phone number.
		if looksLikePhone(p.ContactName) {
			phone = p.ContactName
		} else {
			// Use a synthetic key "name:<name>" to keep contacts separate
			// while clearly marking it's not a real phone number.
			phone = "name:" + p.ContactName
		}
	}
	contact, err := s.repo.UpsertContact(ctx, devID, phone, p.ContactName)
	if err != nil {
		return fmt.Errorf("upsert contact: %w", err)
	}

	// 2. Upsert conversation.
	conv, err := s.repo.UpsertConversation(ctx, devID, contact.ID)
	if err != nil {
		return fmt.Errorf("upsert conversation: %w", err)
	}

	// 3. Build idempotency key if not provided by agent.
	idemKey := p.IdempotencyKey
	if idemKey == "" {
		idemKey = IdempotencyKey(deviceID, p.ContactPhone, p.Content, p.Timestamp)
	}

	// 4. Insert message (idempotent — duplicate silently skipped).
	msg := &models.Message{
		ConversationID: conv.ID,
		DeviceID:       devID,
		ExternalID:     p.ExternalID,
		Direction:      p.Direction,
		Content:        p.Content,
		ContentType:    coalesce(p.ContentType, "text"),
		Status:         "received",
		IdempotencyKey: idemKey,
	}

	created, err := s.repo.InsertMessage(ctx, msg)
	if err != nil {
		return fmt.Errorf("insert message: %w", err)
	}
	if !created {
		s.log.Debug("duplicate message skipped", zap.String("idem_key", idemKey))
		return nil
	}

	// 5. Update conversation summary.
	_ = s.repo.UpdateConversationLastMessage(ctx, conv.ID, p.Content, msg.CreatedAt)

	// 6. Broadcast new message to all CRM operators.
	s.hub.BroadcastJSON(models.WSEnvelope{
		Type:    models.EventTypeNewMessage,
		Payload: mustMarshal(models.NewMessagePayload{ConversationID: conv.ID.String(), Message: *msg}),
	})

	// 7. Broadcast updated conversation so operator lists refresh.
	if updatedConv, err := s.repo.GetConversation(ctx, conv.ID); err == nil {
		s.hub.BroadcastJSON(models.WSEnvelope{
			Type:    models.EventTypeConversationUpd,
			Payload: mustMarshal(models.ConvUpdatedPayload{Conversation: *updatedConv}),
		})
	}

	s.log.Info("message ingested",
		zap.String("conv_id", conv.ID.String()),
		zap.String("direction", p.Direction),
		zap.String("contact", p.ContactPhone),
	)
	return nil
}

func (s *MessageService) handleStatusUpdate(ctx context.Context, deviceID string, p models.StatusUpdatePayload) {
	if p.IdempotencyKey != "" {
		if err := s.repo.UpdateMessageStatus(ctx, p.IdempotencyKey, p.Status, p.ErrorMessage); err != nil {
			s.log.Error("update message status", zap.Error(err))
		}
	}
	if p.CommandID != "" {
		cmdID, err := uuid.Parse(p.CommandID)
		if err == nil {
			if err := s.repo.UpdateCommandStatus(ctx, cmdID, p.Status, p.ErrorMessage); err != nil {
				s.log.Error("update command status", zap.Error(err))
			}
		}
	}

	// Notify operators.
	s.hub.BroadcastJSON(models.WSEnvelope{
		Type:    models.EventTypeCommandAck,
		Payload: mustMarshal(p),
	})
}

// ─── Reply (CRM → Device) ──────────────────────────────────────

// SendReply queues a reply message and returns the created Message record.
func (s *MessageService) SendReply(ctx context.Context, convID, operatorID uuid.UUID, content string) (*models.Message, error) {
	conv, err := s.repo.GetConversation(ctx, convID)
	if err != nil {
		return nil, fmt.Errorf("get conversation: %w", err)
	}

	// 1. Create outbound message record in DB (status = queued).
	idemKey := IdempotencyKey(conv.DeviceID.String(), conv.ContactPhone, content, nowISO())
	msg := &models.Message{
		ConversationID: convID,
		DeviceID:       conv.DeviceID,
		Direction:      "out",
		Content:        content,
		ContentType:    "text",
		Status:         "queued",
		IdempotencyKey: idemKey,
		OperatorID:     &operatorID,
	}
	if _, err := s.repo.InsertMessage(ctx, msg); err != nil {
		return nil, fmt.Errorf("insert message: %w", err)
	}

	// 2. Create command record.
	// Strip synthetic "name:" prefix — agent needs the real phone or empty string.
	cmdPhone := conv.ContactPhone
	if strings.HasPrefix(cmdPhone, "name:") {
		cmdPhone = ""
	}
	cmdPayload, _ := json.Marshal(models.SendMessageCmd{
		CommandID:    uuid.New().String(),
		ContactPhone: cmdPhone,
		ContactName:  conv.ContactName,
		Content:      content,
		ConvID:       convID.String(),
	})
	cmd := &models.DeviceCommand{
		DeviceID:       conv.DeviceID,
		ConversationID: &convID,
		MessageID:      &msg.ID,
		CommandType:    models.CmdTypeSendMessage,
		Payload:        cmdPayload,
		MaxRetries:     5,
	}
	if err := s.repo.CreateCommand(ctx, cmd); err != nil {
		return nil, fmt.Errorf("create command: %w", err)
	}

	// 3. Deliver via agent WebSocket (RemoteInput / Accessibility on phone).
	var sendCmd models.SendMessageCmd
	_ = json.Unmarshal(cmdPayload, &sendCmd)
	envBytes, _ := json.Marshal(models.WSEnvelope{
		Type:    models.CmdTypeSendMessage,
		Payload: mustMarshal(sendCmd),
	})

	if delivered := s.hub.SendToDevice(conv.DeviceID.String(), envBytes); delivered {
		_ = s.repo.UpdateCommandStatus(ctx, cmd.ID, "delivered", "")
	} else {
		// 4b. Device offline → publish to Redis Stream for later delivery.
		if _, err := s.queue.PublishCommand(ctx, conv.DeviceID.String(), sendCmd); err != nil {
			s.log.Error("publish to redis stream", zap.Error(err))
		}
	}

	// 4. Broadcast the new outbound message to operators so UI updates.
	s.hub.BroadcastJSON(models.WSEnvelope{
		Type:    models.EventTypeNewMessage,
		Payload: mustMarshal(models.NewMessagePayload{ConversationID: convID.String(), Message: *msg}),
	})

	return msg, nil
}

// StartDeviceConsumer starts the Redis Stream consumer for a device
// so that any queued commands are delivered when the device connects.
func (s *MessageService) StartDeviceConsumer(ctx context.Context, deviceID string) {
	s.queue.StartConsumer(ctx, deviceID, s.hub, s.log)
}

// ─── helpers ──────────────────────────────────────────────────

// IdempotencyKey generates a stable SHA-256 based key for deduplication.
func IdempotencyKey(parts ...string) string {
	h := sha256.New()
	for _, p := range parts {
		_, _ = h.Write([]byte(p))
		_, _ = h.Write([]byte{0}) // separator
	}
	return fmt.Sprintf("%x", h.Sum(nil))
}

func mustMarshal(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

// looksLikePhone returns true if s contains 7 or more digits.
func looksLikePhone(s string) bool {
	count := 0
	for _, c := range s {
		if c >= '0' && c <= '9' {
			count++
		}
	}
	return count >= 7
}

func coalesce(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func nowISO() string {
	return fmt.Sprintf("%d", time.Now().UnixNano())
}
