// Package queue wraps Redis Streams for durable device-command delivery.
//
// Stream layout:
//
//	commands:{device_id}  — one stream per device, entries are serialised SendMessageCmd
//
// Consumer-group name:    crm-backend
// Consumer instance name: {hostname}-{pid}
package queue

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/crm/backend/internal/models"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

const (
	consumerGroup    = "crm-backend"
	readBlockTimeout = 5 * time.Second
	claimMinIdle     = 30 * time.Second // reclaim messages stuck >30 s
)

// RedisStreams is a thin wrapper around go-redis.
type RedisStreams struct {
	rdb *redis.Client
}

// New creates a RedisStreams instance and verifies connectivity.
func New(ctx context.Context, redisURL string) (*RedisStreams, error) {
	opt, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	rdb := redis.NewClient(opt)
	if err := rdb.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("ping redis: %w", err)
	}
	return &RedisStreams{rdb: rdb}, nil
}

func (rs *RedisStreams) Close() error { return rs.rdb.Close() }

// streamKey returns the per-device stream name.
func streamKey(deviceID string) string { return "commands:" + deviceID }

// PublishCommand puts a SendMessageCmd onto the device-specific stream.
// Returns the Redis stream entry ID.
func (rs *RedisStreams) PublishCommand(ctx context.Context, deviceID string, cmd models.SendMessageCmd) (string, error) {
	payload, err := json.Marshal(cmd)
	if err != nil {
		return "", err
	}
	id, err := rs.rdb.XAdd(ctx, &redis.XAddArgs{
		Stream: streamKey(deviceID),
		MaxLen: 10_000,
		Approx: true,
		Values: map[string]any{
			"command_id":    cmd.CommandID,
			"command_type":  models.CmdTypeSendMessage,
			"device_id":     deviceID,
			"payload":       payload,
		},
	}).Result()
	return id, err
}

// EnsureConsumerGroup creates the consumer group if it does not exist yet.
func (rs *RedisStreams) EnsureConsumerGroup(ctx context.Context, deviceID string) error {
	streamName := streamKey(deviceID)
	err := rs.rdb.XGroupCreateMkStream(ctx, streamName, consumerGroup, "0").Err()
	if err != nil && err.Error() != "BUSYGROUP Consumer Group name already exists" {
		return fmt.Errorf("xgroup create: %w", err)
	}
	return nil
}

// CommandDispatcher is implemented by the WebSocket hub so that the queue
// consumer can push commands to connected devices without a circular import.
type CommandDispatcher interface {
	SendToDevice(deviceID string, envelope []byte) bool
}

// StartConsumer reads pending and new commands for a single device stream,
// delivering them to the hub. Runs until ctx is cancelled.
func (rs *RedisStreams) StartConsumer(ctx context.Context, deviceID string, dispatcher CommandDispatcher, log *zap.Logger) {
	consumer := fmt.Sprintf("%s-%d", hostname(), os.Getpid())
	stream := streamKey(deviceID)

	if err := rs.EnsureConsumerGroup(ctx, deviceID); err != nil {
		log.Error("ensure consumer group", zap.String("device", deviceID), zap.Error(err))
		return
	}

	// First pass: process any previously unacknowledged messages.
	rs.processPending(ctx, stream, deviceID, consumer, dispatcher, log)

	// Continuous read loop.
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		results, err := rs.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    consumerGroup,
			Consumer: consumer,
			Streams:  []string{stream, ">"},
			Count:    10,
			Block:    readBlockTimeout,
			NoAck:    false,
		}).Result()

		if err == redis.Nil || err == context.DeadlineExceeded {
			continue
		}
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Error("xreadgroup", zap.Error(err))
			time.Sleep(2 * time.Second)
			continue
		}

		for _, s := range results {
			for _, msg := range s.Messages {
				rs.deliverMessage(ctx, stream, deviceID, msg, dispatcher, log)
			}
		}
	}
}

func (rs *RedisStreams) deliverMessage(
	ctx context.Context,
	stream, deviceID string,
	msg redis.XMessage,
	dispatcher CommandDispatcher,
	log *zap.Logger,
) {
	payloadBytes, ok := msg.Values["payload"].(string)
	if !ok {
		log.Warn("bad payload field, acking to skip", zap.String("id", msg.ID))
		_ = rs.rdb.XAck(ctx, stream, consumerGroup, msg.ID).Err()
		return
	}

	envelope, err := wrapCommandEnvelope(payloadBytes)
	if err != nil {
		log.Error("wrap command envelope", zap.Error(err))
		_ = rs.rdb.XAck(ctx, stream, consumerGroup, msg.ID).Err()
		return
	}

	delivered := dispatcher.SendToDevice(deviceID, envelope)
	if !delivered {
		// Device is offline — leave in pending list; will be re-delivered when
		// device reconnects and StartConsumer is called again.
		log.Info("device offline, message stays pending",
			zap.String("device", deviceID), zap.String("stream_id", msg.ID))
		return
	}

	if err := rs.rdb.XAck(ctx, stream, consumerGroup, msg.ID).Err(); err != nil {
		log.Error("xack", zap.Error(err))
	}
}

// processPending reclaims XAUTOCLAIM messages that were previously unacked.
func (rs *RedisStreams) processPending(
	ctx context.Context,
	stream, deviceID, consumer string,
	dispatcher CommandDispatcher,
	log *zap.Logger,
) {
	start := "0-0"
	for {
		res, _, err := rs.rdb.XAutoClaim(ctx, &redis.XAutoClaimArgs{
			Stream:   stream,
			Group:    consumerGroup,
			Consumer: consumer,
			MinIdle:  claimMinIdle,
			Start:    start,
			Count:    50,
		}).Result()
		if err != nil {
			log.Error("xautoclaim", zap.Error(err))
			return
		}
		if len(res) == 0 {
			return
		}
		for _, msg := range res {
			rs.deliverMessage(ctx, stream, deviceID, msg, dispatcher, log)
		}
		// If fewer than 50 returned, we've exhausted pending entries.
		if len(res) < 50 {
			return
		}
		start = res[len(res)-1].ID
	}
}

// SetDeviceExpiry sets a TTL on the stream so it is GC'd when the device is
// inactive for a prolonged period.
func (rs *RedisStreams) SetDeviceExpiry(ctx context.Context, deviceID string, ttl time.Duration) error {
	return rs.rdb.Expire(ctx, streamKey(deviceID), ttl).Err()
}

// CacheSet stores a key-value pair with a TTL (general-purpose cache).
func (rs *RedisStreams) CacheSet(ctx context.Context, key string, value any, ttl time.Duration) error {
	b, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return rs.rdb.Set(ctx, key, b, ttl).Err()
}

// CacheGet retrieves a value by key into dst. Returns (false, nil) on miss.
func (rs *RedisStreams) CacheGet(ctx context.Context, key string, dst any) (bool, error) {
	b, err := rs.rdb.Get(ctx, key).Bytes()
	if err == redis.Nil {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal(b, dst)
}

// ─── helpers ──────────────────────────────────────────────────

func wrapCommandEnvelope(payload string) ([]byte, error) {
	env := map[string]json.RawMessage{
		"type":    json.RawMessage(`"` + models.CmdTypeSendMessage + `"`),
		"payload": json.RawMessage(payload),
	}
	return json.Marshal(env)
}

func hostname() string {
	h, _ := os.Hostname()
	if h == "" {
		return "unknown"
	}
	return h
}

// IDToUnixMS extracts the millisecond timestamp from a Redis Stream ID.
func IDToUnixMS(id string) int64 {
	for i, c := range id {
		if c == '-' {
			ms, _ := strconv.ParseInt(id[:i], 10, 64)
			return ms
		}
	}
	return 0
}
