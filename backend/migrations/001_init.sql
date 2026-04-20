-- =============================================================
-- CRM WhatsApp Integration — Database Migration 001
-- =============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Operators (CRM users) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS operators (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'agent',  -- agent | admin
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─── Devices (Android phones running Agent APK) ───────────────
CREATE TABLE IF NOT EXISTS devices (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    token        VARCHAR(512) NOT NULL UNIQUE,   -- JWT device token
    phone_number VARCHAR(50),
    status       VARCHAR(20)  NOT NULL DEFAULT 'offline',  -- online | offline
    last_seen_at TIMESTAMPTZ,
    metadata     JSONB        NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);
CREATE INDEX IF NOT EXISTS idx_devices_token  ON devices(token);

-- ─── Contacts ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS contacts (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id    UUID         NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    phone_number VARCHAR(50)  NOT NULL,
    name         VARCHAR(255) NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (device_id, phone_number)
);

CREATE INDEX IF NOT EXISTS idx_contacts_device_phone ON contacts(device_id, phone_number);

-- ─── Conversations ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversations (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id            UUID        NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    contact_id           UUID        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    assigned_operator_id UUID        REFERENCES operators(id) ON DELETE SET NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'open',  -- open | closed | archived
    unread_count         INT         NOT NULL DEFAULT 0,
    last_message_at      TIMESTAMPTZ,
    last_message_text    TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (device_id, contact_id)
);

CREATE INDEX IF NOT EXISTS idx_conversations_device    ON conversations(device_id);
CREATE INDEX IF NOT EXISTS idx_conversations_status    ON conversations(status);
CREATE INDEX IF NOT EXISTS idx_conversations_last_msg  ON conversations(last_message_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_conversations_operator  ON conversations(assigned_operator_id);

-- ─── Messages ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS messages (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID         NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    device_id        UUID         NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    external_id      VARCHAR(255),                    -- WhatsApp internal msg ID (if known)
    direction        VARCHAR(5)   NOT NULL,            -- 'in' | 'out'
    content          TEXT         NOT NULL,
    content_type     VARCHAR(50)  NOT NULL DEFAULT 'text',
    status           VARCHAR(20)  NOT NULL DEFAULT 'received',
    -- received | queued | sent | delivered | read | failed
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,    -- SHA256(device_id+contact_phone+content+ts)
    error_message    TEXT,
    operator_id      UUID         REFERENCES operators(id) ON DELETE SET NULL,
    raw_payload      JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at          TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    read_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_device       ON messages(device_id);
CREATE INDEX IF NOT EXISTS idx_messages_status       ON messages(status);
CREATE INDEX IF NOT EXISTS idx_messages_idem_key     ON messages(idempotency_key);

-- ─── Device Commands (outbound queue) ─────────────────────────
CREATE TABLE IF NOT EXISTS device_commands (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id       UUID         NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    conversation_id UUID         REFERENCES conversations(id)  ON DELETE SET NULL,
    message_id      UUID         REFERENCES messages(id)       ON DELETE SET NULL,
    command_type    VARCHAR(50)  NOT NULL,   -- 'send_message'
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    -- pending | delivered | completed | failed
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 3,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at    TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_commands_device_status ON device_commands(device_id, status);
CREATE INDEX IF NOT EXISTS idx_commands_status        ON device_commands(status, created_at);

-- ─── Triggers: updated_at ─────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_contacts_updated_at
    BEFORE UPDATE ON contacts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ─── Seed: default admin operator ─────────────────────────────
-- Password: admin123 (bcrypt — change in production!)
INSERT INTO operators (email, name, password_hash, role)
VALUES (
    'admin@crm.local',
    'Admin',
    '$2a$10$uLp4LefCemXvqawbrF095e.Wti38rw6sr2jmOLapKteuS.S8zlbMy',
    'admin'
) ON CONFLICT DO NOTHING;
