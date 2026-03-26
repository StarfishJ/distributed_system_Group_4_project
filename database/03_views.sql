-- CS6650 Assignment 3: Materialized Views for Analytics
-- Run after 02_indexes.sql
--
-- Materialized views speed up analytics queries by pre-aggregating.
-- Refresh strategy: REFRESH CONCURRENTLY (non-blocking) on schedule or after bulk load.

-- =============================================================================
-- Query 4 optimization: User's rooms with last activity
-- Target: < 50ms
-- =============================================================================
CREATE MATERIALIZED VIEW mv_user_rooms AS
SELECT
    user_id,
    room_id,
    MAX(timestamp_utc) AS last_activity,
    COUNT(*) AS message_count
FROM messages
GROUP BY user_id, room_id;

CREATE UNIQUE INDEX idx_mv_user_rooms_pk ON mv_user_rooms (user_id, room_id);
CREATE INDEX idx_mv_user_rooms_user ON mv_user_rooms (user_id);

-- =============================================================================
-- Analytics: Messages per minute (for messages/sec statistics)
-- =============================================================================
CREATE MATERIALIZED VIEW mv_messages_per_minute AS
SELECT
    date_trunc('minute', timestamp_utc) AS minute_bucket,
    COUNT(*) AS message_count
FROM messages
GROUP BY date_trunc('minute', timestamp_utc);

CREATE INDEX idx_mv_messages_per_minute ON mv_messages_per_minute (minute_bucket);

-- =============================================================================
-- Analytics: Most active users (pre-aggregated)
-- =============================================================================
CREATE MATERIALIZED VIEW mv_user_activity AS
SELECT
    user_id,
    COUNT(*) AS message_count
FROM messages
GROUP BY user_id;

CREATE INDEX idx_mv_user_activity_count ON mv_user_activity (message_count DESC);

-- =============================================================================
-- Analytics: Most active rooms (pre-aggregated)
-- =============================================================================
CREATE MATERIALIZED VIEW mv_room_activity AS
SELECT
    room_id,
    COUNT(*) AS message_count
FROM messages
GROUP BY room_id;

CREATE INDEX idx_mv_room_activity_count ON mv_room_activity (message_count DESC);

-- =============================================================================
-- Refresh commands (run periodically or after load tests)
-- =============================================================================
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_rooms;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_messages_per_minute;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_activity;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_room_activity;
