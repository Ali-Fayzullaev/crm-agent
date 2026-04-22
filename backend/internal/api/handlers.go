package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/crm/backend/internal/models"
	"github.com/crm/backend/internal/repository"
	"github.com/crm/backend/internal/service"
	"github.com/crm/backend/internal/ws"
	"github.com/go-chi/chi/v5"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"
	"golang.org/x/crypto/bcrypt"
)

// ─── Context keys ─────────────────────────────────────────────

type ctxKey string

const (
	ctxDeviceID   ctxKey = "device_id"
	ctxOperatorID ctxKey = "operator_id"
	ctxRole       ctxKey = "role"
)

func contextWithDeviceID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, ctxDeviceID, id)
}

func contextWithOperator(ctx context.Context, id, role string) context.Context {
	ctx = context.WithValue(ctx, ctxOperatorID, id)
	return context.WithValue(ctx, ctxRole, role)
}

// ─── handlers struct ──────────────────────────────────────────

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(_ *http.Request) bool { return true }, // auth is JWT-based
}

type handlers struct {
	svc       *service.MessageService
	hub       *ws.Hub
	repo      *repository.Repository
	log       *zap.Logger
	jwtSecret []byte
}

// ─── Auth ──────────────────────────────────────────────────────

func (h *handlers) operatorLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid body", http.StatusBadRequest)
		return
	}
	if req.Email == "" || req.Password == "" {
		jsonError(w, "email and password required", http.StatusBadRequest)
		return
	}

	op, err := h.repo.GetOperatorByEmail(r.Context(), req.Email)
	if err != nil {
		jsonError(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(op.PasswordHash), []byte(req.Password)); err != nil {
		jsonError(w, "invalid credentials", http.StatusUnauthorized)
		return
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		claimSubject: op.ID.String(),
		claimRole:    op.Role,
		"name":       op.Name,
		"exp":        time.Now().Add(24 * time.Hour).Unix(),
	})
	signed, err := token.SignedString(h.jwtSecret)
	if err != nil {
		jsonError(w, "token generation failed", http.StatusInternalServerError)
		return
	}

	jsonOK(w, map[string]any{"token": signed, "operator": op})
}

// ─── Device registration ──────────────────────────────────────

func (h *handlers) registerDevice(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        string `json:"name"`
		PhoneNumber string `json:"phone_number"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		jsonError(w, "name required", http.StatusBadRequest)
		return
	}

	// Create a long-lived device JWT (role=device).
	deviceID := uuid.New()
	rawToken := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		claimDeviceID: deviceID.String(),
		claimRole:     "device",
		// No expiry — device tokens are rotated on re-registration.
	})
	signed, err := rawToken.SignedString(h.jwtSecret)
	if err != nil {
		jsonError(w, "token error", http.StatusInternalServerError)
		return
	}

	dev := &models.Device{
		ID:          deviceID,
		Name:        req.Name,
		Token:       signed,
		PhoneNumber: req.PhoneNumber,
	}
	if err := h.repo.CreateDevice(r.Context(), dev); err != nil {
		h.log.Error("create device", zap.Error(err))
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}

	jsonOK(w, map[string]any{"device": dev, "token": signed})
}

func (h *handlers) listDevices(w http.ResponseWriter, r *http.Request) {
	devices, err := h.repo.ListDevices(r.Context())
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonOK(w, map[string]any{"devices": devices})
}

// ─── Agent WebSocket ──────────────────────────────────────────

func (h *handlers) agentWebSocket(w http.ResponseWriter, r *http.Request) {
	deviceID, _ := r.Context().Value(ctxDeviceID).(string)

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.log.Error("ws upgrade (agent)", zap.Error(err))
		return
	}

	devUUID, _ := uuid.Parse(deviceID)
	_ = h.repo.SetDeviceStatus(r.Context(), devUUID, "online")

	client := ws.NewAgentClient(h.hub, conn, deviceID)

	// Start Redis Stream consumer for this device so queued commands are flushed.
	go h.svc.StartDeviceConsumer(r.Context(), deviceID)

	client.Run() // blocks until disconnect

	_ = h.repo.SetDeviceStatus(context.Background(), devUUID, "offline")
}

// ─── Operator WebSocket ───────────────────────────────────────

func (h *handlers) operatorWebSocket(w http.ResponseWriter, r *http.Request) {
	operatorIDStr, _ := r.Context().Value(ctxOperatorID).(string)
	operatorID, err := uuid.Parse(operatorIDStr)
	if err != nil {
		jsonError(w, "invalid operator id in token", http.StatusUnauthorized)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.log.Error("ws upgrade (operator)", zap.Error(err))
		return
	}

	client := ws.NewOperatorClient(h.hub, conn, operatorID)
	client.Run() // blocks
}

// ─── Conversations ────────────────────────────────────────────

func (h *handlers) listConversations(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	limit := queryInt(r, "limit", 50)
	offset := queryInt(r, "offset", 0)

	convs, err := h.repo.ListConversations(r.Context(), status, limit, offset)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonOK(w, map[string]any{"conversations": convs, "limit": limit, "offset": offset})
}

func (h *handlers) getConversation(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	convID, err := uuid.Parse(id)
	if err != nil {
		jsonError(w, "invalid id", http.StatusBadRequest)
		return
	}
	if changed, err := h.repo.MarkConversationRead(r.Context(), convID); err == nil && changed {
		if updated, err := h.repo.GetConversation(r.Context(), convID); err == nil {
			h.hub.BroadcastJSON(models.WSEnvelope{
				Type:    models.EventTypeConversationUpd,
				Payload: mustMarshalRaw(models.ConvUpdatedPayload{Conversation: *updated}),
			})
		}
	}
	conv, err := h.repo.GetConversation(r.Context(), convID)
	if err != nil {
		jsonError(w, "not found", http.StatusNotFound)
		return
	}
	jsonOK(w, conv)
}

func (h *handlers) updateConversation(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	convID, err := uuid.Parse(id)
	if err != nil {
		jsonError(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req struct {
		Status             *string `json:"status"`
		AssignedOperatorID *string `json:"assigned_operator_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "invalid body", http.StatusBadRequest)
		return
	}

	if req.Status != nil {
		if err := h.repo.UpdateConversationStatus(r.Context(), convID, *req.Status); err != nil {
			jsonError(w, "update failed", http.StatusInternalServerError)
			return
		}
	}
	if req.AssignedOperatorID != nil {
		opID, err := uuid.Parse(*req.AssignedOperatorID)
		if err != nil {
			jsonError(w, "invalid operator id", http.StatusBadRequest)
			return
		}
		if err := h.repo.AssignOperator(r.Context(), convID, opID); err != nil {
			jsonError(w, "assign failed", http.StatusInternalServerError)
			return
		}
	}

	conv, _ := h.repo.GetConversation(r.Context(), convID)
	if conv != nil {
		h.hub.BroadcastJSON(models.WSEnvelope{
			Type:    models.EventTypeConversationUpd,
			Payload: mustMarshalRaw(models.ConvUpdatedPayload{Conversation: *conv}),
		})
	}

	jsonOK(w, map[string]any{"ok": true})
}

// ─── Messages ────────────────────────────────────────────────

func (h *handlers) listMessages(w http.ResponseWriter, r *http.Request) {
	convID, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		jsonError(w, "invalid id", http.StatusBadRequest)
		return
	}
	if changed, err := h.repo.MarkConversationRead(r.Context(), convID); err == nil && changed {
		if updated, err := h.repo.GetConversation(r.Context(), convID); err == nil {
			h.hub.BroadcastJSON(models.WSEnvelope{
				Type:    models.EventTypeConversationUpd,
				Payload: mustMarshalRaw(models.ConvUpdatedPayload{Conversation: *updated}),
			})
		}
	}
	limit := queryInt(r, "limit", 100)
	offset := queryInt(r, "offset", 0)

	msgs, err := h.repo.ListMessages(r.Context(), convID, limit, offset)
	if err != nil {
		jsonError(w, "internal error", http.StatusInternalServerError)
		return
	}
	jsonOK(w, map[string]any{"messages": msgs, "limit": limit, "offset": offset})
}

func (h *handlers) sendReply(w http.ResponseWriter, r *http.Request) {
	convID, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		jsonError(w, "invalid conversation id", http.StatusBadRequest)
		return
	}

	var req struct {
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Content == "" {
		jsonError(w, "content required", http.StatusBadRequest)
		return
	}

	operatorIDStr, _ := r.Context().Value(ctxOperatorID).(string)
	operatorID, _ := uuid.Parse(operatorIDStr)

	msg, err := h.svc.SendReply(r.Context(), convID, operatorID, req.Content)
	if err != nil {
		h.log.Error("send reply", zap.Error(err))
		jsonError(w, "failed to queue reply", http.StatusInternalServerError)
		return
	}

	jsonOK(w, map[string]any{"message": msg})
}

// ─── helpers ──────────────────────────────────────────────────

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, msg string, code int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func queryInt(r *http.Request, key string, def int) int {
	v := r.URL.Query().Get(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return def
	}
	return n
}

func mustMarshalRaw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

// Keep fmt imported (used implicitly via Sprintf in router.go).
var _ = fmt.Sprintf
