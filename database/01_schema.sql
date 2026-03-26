-- CS6650 Assignment 3: Chat Message Persistence Schema
-- PostgreSQL 14+
-- Run order: 01_schema.sql -> 02_indexes.sql -> 03_views.sql

-- Drop existing objects (for clean re-run)
DROP MATERIALIZED VIEW IF EXISTS mv_user_rooms CASCADE;
DROP MATERIALIZED VIEW IF EXISTS mv_messages_per_minute CASCADE;
DROP MATERIALIZED VIEW IF EXISTS mv_user_activity CASCADE;
DROP MATERIALIZED VIEW IF EXISTS mv_room_activity CASCADE;
DROP TABLE IF EXISTS messages CASCADE;

-- =============================================================================
-- Core Table: messages
-- =============================================================================
-- Stores all chat messages. message_id is the idempotency key (UUID from client).
-- timestamp_utc: parsed from ClientMessage.timestamp (ISO-8601) for efficient range queries
-- timestamp_raw: original string for debugging/audit
CREATE TABLE messages (
    message_id    VARCHAR(64)   NOT NULL PRIMARY KEY,
    room_id       VARCHAR(64)   NOT NULL,
    user_id       VARCHAR(64)   NOT NULL,
    username      VARCHAR(128),
    message       TEXT,
    message_type  VARCHAR(32),
    timestamp_raw VARCHAR(64),  -- original ISO-8601 from client
    timestamp_utc TIMESTAMPTZ   NOT NULL,
    server_id     VARCHAR(64),
    client_ip     VARCHAR(45),
    created_at    TIMESTAMPTZ   DEFAULT NOW()
);

COMMENT ON TABLE messages IS 'Chat messages with idempotent upsert by message_id';
COMMENT ON COLUMN messages.message_id IS 'UUID from client, used for idempotent writes';
COMMENT ON COLUMN messages.timestamp_utc IS 'Parsed timestamp for efficient time-range queries';
