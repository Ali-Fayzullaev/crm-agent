// Package ws implements the central WebSocket hub that manages two client
// categories:
//
//  1. Agent clients  — Android APKs, authenticated by device JWT.
//  2. Operator clients — CRM web-app sessions, authenticated by operator JWT.
//
// The hub is the single goroutine that owns the maps; all mutations go through
// channels to avoid races.
package ws

import (
	"encoding/json"
	"sync"
	"time"

	"github.com/crm/backend/internal/models"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = 50 * time.Second // must be < pongWait
	maxMessageSize = 64 * 1024        // 64 KB
)

// ─── Hub ──────────────────────────────────────────────────────

// Hub is the central broker between agents and operators.
type Hub struct {
	log *zap.Logger

	// mu guards agents and operators maps.
	mu        sync.RWMutex
	agents    map[string]*AgentClient    // device_id → client
	operators map[uuid.UUID]*OperatorClient

	// Lifecycle channels
	regAgent     chan *AgentClient
	unregAgent   chan *AgentClient
	regOperator  chan *OperatorClient
	unregOperator chan *OperatorClient

	// Broadcast to all connected operators
	broadcast chan []byte

	// Event hooks (set by service layer after construction)
	OnAgentMessage func(deviceID string, raw []byte)
}

func NewHub(log *zap.Logger) *Hub {
	return &Hub{
		log:           log,
		agents:        make(map[string]*AgentClient),
		operators:     make(map[uuid.UUID]*OperatorClient),
		regAgent:      make(chan *AgentClient, 32),
		unregAgent:    make(chan *AgentClient, 32),
		regOperator:   make(chan *OperatorClient, 32),
		unregOperator: make(chan *OperatorClient, 32),
		broadcast:     make(chan []byte, 256),
	}
}

// Run is the hub's main event loop. Must be started as a goroutine.
func (h *Hub) Run() {
	for {
		select {
		case c := <-h.regAgent:
			h.mu.Lock()
			h.agents[c.DeviceID] = c
			h.mu.Unlock()
			h.log.Info("agent connected", zap.String("device_id", c.DeviceID))
			h.BroadcastJSON(models.WSEnvelope{
				Type:    models.EventTypeDeviceStatus,
				Payload: mustMarshal(models.DeviceStatusPayload{DeviceID: c.DeviceID, Status: "online"}),
			})

		case c := <-h.unregAgent:
			h.mu.Lock()
			if h.agents[c.DeviceID] == c {
				delete(h.agents, c.DeviceID)
			}
			h.mu.Unlock()
			h.log.Info("agent disconnected", zap.String("device_id", c.DeviceID))
			h.BroadcastJSON(models.WSEnvelope{
				Type:    models.EventTypeDeviceStatus,
				Payload: mustMarshal(models.DeviceStatusPayload{DeviceID: c.DeviceID, Status: "offline"}),
			})

		case c := <-h.regOperator:
			h.mu.Lock()
			h.operators[c.OperatorID] = c
			h.mu.Unlock()
			h.log.Info("operator connected", zap.String("operator_id", c.OperatorID.String()))

		case c := <-h.unregOperator:
			h.mu.Lock()
			delete(h.operators, c.OperatorID)
			h.mu.Unlock()
			h.log.Info("operator disconnected", zap.String("operator_id", c.OperatorID.String()))

		case msg := <-h.broadcast:
			h.mu.RLock()
			for _, op := range h.operators {
				select {
				case op.send <- msg:
				default:
					h.log.Warn("operator send buffer full, dropping",
						zap.String("operator_id", op.OperatorID.String()))
				}
			}
			h.mu.RUnlock()
		}
	}
}

// ─── Hub public API ───────────────────────────────────────────

// SendToDevice sends a raw JSON envelope to a specific device agent.
// Returns false if the device is not currently connected.
func (h *Hub) SendToDevice(deviceID string, envelope []byte) bool {
	h.mu.RLock()
	c, ok := h.agents[deviceID]
	h.mu.RUnlock()
	if !ok {
		return false
	}
	select {
	case c.send <- envelope:
		return true
	default:
		h.log.Warn("agent send buffer full", zap.String("device_id", deviceID))
		return false
	}
}

// BroadcastJSON serialises env and fans it out to all connected operators.
func (h *Hub) BroadcastJSON(env models.WSEnvelope) {
	b, err := json.Marshal(env)
	if err != nil {
		h.log.Error("broadcast marshal", zap.Error(err))
		return
	}
	select {
	case h.broadcast <- b:
	default:
		h.log.Warn("broadcast channel full, dropping")
	}
}

// IsDeviceOnline returns true if the device has an active WebSocket connection.
func (h *Hub) IsDeviceOnline(deviceID string) bool {
	h.mu.RLock()
	_, ok := h.agents[deviceID]
	h.mu.RUnlock()
	return ok
}

// ─── Agent client ──────────────────────────────────────────────

// AgentClient represents a connected Android device agent.
type AgentClient struct {
	DeviceID string
	hub      *Hub
	conn     *websocket.Conn
	send     chan []byte
}

// NewAgentClient creates and registers a new agent connection.
func NewAgentClient(hub *Hub, conn *websocket.Conn, deviceID string) *AgentClient {
	c := &AgentClient{
		DeviceID: deviceID,
		hub:      hub,
		conn:     conn,
		send:     make(chan []byte, 128),
	}
	hub.regAgent <- c
	return c
}

// Run starts the read and write pumps. Blocks until the connection closes.
func (c *AgentClient) Run() {
	go c.writePump()
	c.readPump() // blocks
}

func (c *AgentClient) readPump() {
	defer func() {
		c.hub.unregAgent <- c
		c.conn.Close()
	}()

	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		_, raw, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err,
				websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				c.hub.log.Warn("agent ws read error",
					zap.String("device_id", c.DeviceID), zap.Error(err))
			}
			return
		}
		if c.hub.OnAgentMessage != nil {
			c.hub.OnAgentMessage(c.DeviceID, raw)
		}
	}
}

func (c *AgentClient) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()

	for {
		select {
		case msg, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				c.hub.log.Error("agent ws write",
					zap.String("device_id", c.DeviceID), zap.Error(err))
				return
			}

		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// ─── Operator client ───────────────────────────────────────────

// OperatorClient represents a connected CRM web-app session.
type OperatorClient struct {
	OperatorID uuid.UUID
	hub        *Hub
	conn       *websocket.Conn
	send       chan []byte
}

// NewOperatorClient creates and registers a new operator connection.
func NewOperatorClient(hub *Hub, conn *websocket.Conn, operatorID uuid.UUID) *OperatorClient {
	c := &OperatorClient{
		OperatorID: operatorID,
		hub:        hub,
		conn:       conn,
		send:       make(chan []byte, 256),
	}
	hub.regOperator <- c
	return c
}

// Run starts the read and write pumps. Blocks until the connection closes.
func (c *OperatorClient) Run() {
	go c.writePump()
	c.readPump() // blocks (operators don't send messages via WS — they use REST)
}

func (c *OperatorClient) readPump() {
	defer func() {
		c.hub.unregOperator <- c
		c.conn.Close()
	}()

	c.conn.SetReadLimit(1024)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	// Drain any control frames (pings) the browser may send.
	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return
		}
	}
}

func (c *OperatorClient) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()

	for {
		select {
		case msg, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}

		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// ─── helpers ──────────────────────────────────────────────────

func mustMarshal(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
