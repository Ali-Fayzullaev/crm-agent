package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/crm/backend/internal/models"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Repository is the primary data-access layer backed by PostgreSQL.
type Repository struct {
	pool *pgxpool.Pool
}

// New opens a pgxpool connection and returns a Repository.
func New(ctx context.Context, dsn string) (*Repository, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse dsn: %w", err)
	}
	cfg.MaxConns = 30
	cfg.MinConns = 3
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("open pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}
	return &Repository{pool: pool}, nil
}

func (r *Repository) Close() { r.pool.Close() }

// ─── Device ───────────────────────────────────────────────────

func (r *Repository) GetDeviceByToken(ctx context.Context, token string) (*models.Device, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, name, token, phone_number, status, last_seen_at, created_at, updated_at
		FROM devices WHERE token = $1`, token)
	return scanDevice(row)
}

func (r *Repository) GetDeviceByID(ctx context.Context, id uuid.UUID) (*models.Device, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, name, token, phone_number, status, last_seen_at, created_at, updated_at
		FROM devices WHERE id = $1`, id)
	return scanDevice(row)
}

func (r *Repository) CreateDevice(ctx context.Context, d *models.Device) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO devices (id, name, token, phone_number, status)
		VALUES ($1, $2, $3, $4, 'offline')
		RETURNING created_at, updated_at`,
		d.ID, d.Name, d.Token, d.PhoneNumber,
	).Scan(&d.CreatedAt, &d.UpdatedAt)
}

func (r *Repository) SetDeviceStatus(ctx context.Context, id uuid.UUID, status string) error {
	now := time.Now()
	_, err := r.pool.Exec(ctx, `
		UPDATE devices SET status = $1, last_seen_at = $2 WHERE id = $3`,
		status, now, id)
	return err
}

func (r *Repository) ListDevices(ctx context.Context) ([]*models.Device, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, name, token, phone_number, status, last_seen_at, created_at, updated_at
		FROM devices ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*models.Device
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func scanDevice(row pgx.Row) (*models.Device, error) {
	d := &models.Device{}
	err := row.Scan(
		&d.ID, &d.Name, &d.Token, &d.PhoneNumber,
		&d.Status, &d.LastSeenAt, &d.CreatedAt, &d.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return d, nil
}

// ─── Contact ──────────────────────────────────────────────────

// UpsertContact inserts or updates a contact and returns it.
func (r *Repository) UpsertContact(ctx context.Context, deviceID uuid.UUID, phone, name string) (*models.Contact, error) {
	c := &models.Contact{}
	err := r.pool.QueryRow(ctx, `
		INSERT INTO contacts (device_id, phone_number, name)
		VALUES ($1, $2, $3)
		ON CONFLICT (device_id, phone_number)
		DO UPDATE SET name = EXCLUDED.name, updated_at = NOW()
		RETURNING id, device_id, phone_number, name, created_at, updated_at`,
		deviceID, phone, name,
	).Scan(&c.ID, &c.DeviceID, &c.PhoneNumber, &c.Name, &c.CreatedAt, &c.UpdatedAt)
	return c, err
}

// ─── Conversation ─────────────────────────────────────────────

// UpsertConversation finds or creates a conversation for the (device, contact) pair.
func (r *Repository) UpsertConversation(ctx context.Context, deviceID, contactID uuid.UUID) (*models.Conversation, error) {
	cv := &models.Conversation{}
	err := r.pool.QueryRow(ctx, `
		INSERT INTO conversations (device_id, contact_id)
		VALUES ($1, $2)
		ON CONFLICT (device_id, contact_id) DO UPDATE
		  SET updated_at = NOW()
		RETURNING id, device_id, contact_id, assigned_operator_id,
		          status, unread_count, last_message_at, COALESCE(last_message_text, ''),
		          created_at, updated_at`,
		deviceID, contactID,
	).Scan(
		&cv.ID, &cv.DeviceID, &cv.ContactID, &cv.AssignedOperatorID,
		&cv.Status, &cv.UnreadCount, &cv.LastMessageAt, &cv.LastMessageText,
		&cv.CreatedAt, &cv.UpdatedAt,
	)
	return cv, err
}

func (r *Repository) GetConversation(ctx context.Context, id uuid.UUID) (*models.Conversation, error) {
	cv := &models.Conversation{}
	err := r.pool.QueryRow(ctx, `
		SELECT c.id, c.device_id, c.contact_id, c.assigned_operator_id,
		       c.status, c.unread_count, c.last_message_at, COALESCE(c.last_message_text, ''),
		       c.created_at, c.updated_at,
		       ct.name AS contact_name, ct.phone_number AS contact_phone,
		       d.name  AS device_name
		FROM conversations c
		JOIN contacts ct ON ct.id = c.contact_id
		JOIN devices  d  ON d.id  = c.device_id
		WHERE c.id = $1`, id,
	).Scan(
		&cv.ID, &cv.DeviceID, &cv.ContactID, &cv.AssignedOperatorID,
		&cv.Status, &cv.UnreadCount, &cv.LastMessageAt, &cv.LastMessageText,
		&cv.CreatedAt, &cv.UpdatedAt,
		&cv.ContactName, &cv.ContactPhone, &cv.DeviceName,
	)
	return cv, err
}

func (r *Repository) ListConversations(ctx context.Context, status string, limit, offset int) ([]*models.Conversation, error) {
	query := `
		SELECT c.id, c.device_id, c.contact_id, c.assigned_operator_id,
		       c.status, c.unread_count, c.last_message_at, COALESCE(c.last_message_text, ''),
		       c.created_at, c.updated_at,
		       ct.name AS contact_name, ct.phone_number AS contact_phone,
		       d.name  AS device_name
		FROM conversations c
		JOIN contacts ct ON ct.id = c.contact_id
		JOIN devices  d  ON d.id  = c.device_id`

	args := []any{}
	if status != "" {
		query += " WHERE c.status = $1"
		args = append(args, status)
	}
	query += " ORDER BY c.last_message_at DESC NULLS LAST LIMIT $" +
		fmt.Sprint(len(args)+1) + " OFFSET $" + fmt.Sprint(len(args)+2)
	args = append(args, limit, offset)

	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*models.Conversation
	for rows.Next() {
		cv := &models.Conversation{}
		if err := rows.Scan(
			&cv.ID, &cv.DeviceID, &cv.ContactID, &cv.AssignedOperatorID,
			&cv.Status, &cv.UnreadCount, &cv.LastMessageAt, &cv.LastMessageText,
			&cv.CreatedAt, &cv.UpdatedAt,
			&cv.ContactName, &cv.ContactPhone, &cv.DeviceName,
		); err != nil {
			return nil, err
		}
		out = append(out, cv)
	}
	return out, rows.Err()
}

func (r *Repository) UpdateConversationLastMessage(ctx context.Context, convID uuid.UUID, text string, ts time.Time) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE conversations
		SET last_message_at   = $1,
		    last_message_text = $2,
		    unread_count      = unread_count + 1
		WHERE id = $3`, ts, text, convID)
	return err
}

// MarkConversationRead clears unread counter for a conversation.
// Returns true when counter was changed.
func (r *Repository) MarkConversationRead(ctx context.Context, convID uuid.UUID) (bool, error) {
	tag, err := r.pool.Exec(ctx, `
		UPDATE conversations
		SET unread_count = 0,
		    updated_at = NOW()
		WHERE id = $1 AND unread_count > 0`, convID)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() > 0, nil
}

func (r *Repository) AssignOperator(ctx context.Context, convID, operatorID uuid.UUID) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE conversations SET assigned_operator_id = $1 WHERE id = $2`,
		operatorID, convID)
	return err
}

func (r *Repository) UpdateConversationStatus(ctx context.Context, convID uuid.UUID, status string) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE conversations SET status = $1 WHERE id = $2`, status, convID)
	return err
}

// ─── Message ──────────────────────────────────────────────────

// InsertMessage stores a message idempotently.
// If idempotency_key already exists it returns the existing record without error.
func (r *Repository) InsertMessage(ctx context.Context, m *models.Message) (created bool, err error) {
	tag, err := r.pool.Exec(ctx, `
		INSERT INTO messages
		  (conversation_id, device_id, external_id, direction, content,
		   content_type, status, idempotency_key, operator_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
		ON CONFLICT (idempotency_key) DO NOTHING`,
		m.ConversationID, m.DeviceID, m.ExternalID, m.Direction, m.Content,
		m.ContentType, m.Status, m.IdempotencyKey, m.OperatorID,
	)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		// duplicate — fetch existing
		return false, r.pool.QueryRow(ctx, `
			SELECT id, created_at FROM messages WHERE idempotency_key = $1`,
			m.IdempotencyKey,
		).Scan(&m.ID, &m.CreatedAt)
	}
	return true, r.pool.QueryRow(ctx, `
		SELECT id, created_at FROM messages WHERE idempotency_key = $1`,
		m.IdempotencyKey,
	).Scan(&m.ID, &m.CreatedAt)
}

func (r *Repository) ListMessages(ctx context.Context, convID uuid.UUID, limit, offset int) ([]*models.Message, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, conversation_id, device_id, COALESCE(external_id, ''), direction,
		       content, content_type, status, idempotency_key,
		       COALESCE(error_message, ''), operator_id, created_at, sent_at, delivered_at, read_at
		FROM messages
		WHERE conversation_id = $1
		ORDER BY created_at ASC
		LIMIT $2 OFFSET $3`, convID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*models.Message
	for rows.Next() {
		msg := &models.Message{}
		if err := rows.Scan(
			&msg.ID, &msg.ConversationID, &msg.DeviceID, &msg.ExternalID, &msg.Direction,
			&msg.Content, &msg.ContentType, &msg.Status, &msg.IdempotencyKey,
			&msg.ErrorMessage, &msg.OperatorID, &msg.CreatedAt,
			&msg.SentAt, &msg.DeliveredAt, &msg.ReadAt,
		); err != nil {
			return nil, err
		}
		out = append(out, msg)
	}
	return out, rows.Err()
}

func (r *Repository) UpdateMessageStatus(ctx context.Context, idemKey, status, errMsg string) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE messages SET status = $1, error_message = $2 WHERE idempotency_key = $3`,
		status, errMsg, idemKey)
	return err
}

// ─── Device Command ───────────────────────────────────────────

func (r *Repository) CreateCommand(ctx context.Context, cmd *models.DeviceCommand) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO device_commands
		  (device_id, conversation_id, message_id, command_type, payload, status, max_retries)
		VALUES ($1,$2,$3,$4,$5,'pending',$6)
		RETURNING id, created_at`,
		cmd.DeviceID, cmd.ConversationID, cmd.MessageID, cmd.CommandType, cmd.Payload, cmd.MaxRetries,
	).Scan(&cmd.ID, &cmd.CreatedAt)
}

func (r *Repository) UpdateCommandStatus(ctx context.Context, id uuid.UUID, status, errMsg string) error {
	now := time.Now()
	if status == "delivered" {
		_, err := r.pool.Exec(ctx,
			`UPDATE device_commands SET status=$1, delivered_at=$2 WHERE id=$3`,
			status, now, id)
		return err
	}
	if status == "completed" || status == "failed" {
		_, err := r.pool.Exec(ctx,
			`UPDATE device_commands SET status=$1, error_message=$2, processed_at=$3 WHERE id=$4`,
			status, errMsg, now, id)
		return err
	}
	_, err := r.pool.Exec(ctx,
		`UPDATE device_commands SET status=$1, error_message=$2 WHERE id=$3`,
		status, errMsg, id)
	return err
}

func (r *Repository) IncrementCommandRetry(ctx context.Context, id uuid.UUID) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE device_commands SET retry_count = retry_count + 1 WHERE id = $1`, id)
	return err
}

// ─── Operator ─────────────────────────────────────────────────

func (r *Repository) GetOperatorByEmail(ctx context.Context, email string) (*models.Operator, error) {
	op := &models.Operator{}
	err := r.pool.QueryRow(ctx, `
		SELECT id, email, name, password_hash, role, created_at
		FROM operators WHERE email = $1`, email,
	).Scan(&op.ID, &op.Email, &op.Name, &op.PasswordHash, &op.Role, &op.CreatedAt)
	if err != nil {
		return nil, err
	}
	return op, nil
}
