-- CS6650 Assignment 3: Index Strategy
-- Run after 01_schema.sql
--
-- Custom time windows in GET /metrics (startTime/endTime) only change how many rows match; they do not replace
-- indexes. Latency targets are met by these indexes + LIMITs + materialized views (Core 4); measure with
-- EXPLAIN (ANALYZE, BUFFERS) and your data volume. Label demo windows clearly in the report.
--
-- Index selection rationale:
-- - Core queries 1-4: direct support
-- - Analytics: supported by indexes + materialized views (03_views.sql)
-- - Write performance: minimize number of indexes; composite indexes reduce total

-- =============================================================================
-- Core Query 1: Get messages for a room in time range
-- Input: roomId, startTime, endTime
-- Target: < 100ms for 1000 messages
-- =============================================================================
CREATE INDEX idx_messages_room_time
    ON messages (room_id, timestamp_utc ASC);

-- =============================================================================
-- Core Query 2: Get user's message history
-- Input: userId, optional date range
-- Target: < 200ms
-- =============================================================================
CREATE INDEX idx_messages_user_time
    ON messages (user_id, timestamp_utc ASC);

-- =============================================================================
-- Core Query 3: Count active users in time window
-- Input: startTime, endTime
-- Target: < 500ms
-- Strategy: range scan on timestamp, then COUNT(DISTINCT user_id)
-- =============================================================================
CREATE INDEX idx_messages_timestamp_user
    ON messages (timestamp_utc, user_id);

-- =============================================================================
-- Core Query 4: Get rooms user has participated in (with last activity)
-- Input: userId
-- Target: < 50ms
-- Strategy: (user_id, room_id, timestamp_utc) for GROUP BY room_id, MAX(timestamp)
-- =============================================================================
CREATE INDEX idx_messages_user_room_time
    ON messages (user_id, room_id, timestamp_utc DESC);

-- =============================================================================
-- Analytics: Most active users (top N) - count by user_id
-- =============================================================================
-- idx_messages_user_time already supports: SELECT user_id, COUNT(*) FROM messages GROUP BY user_id

-- =============================================================================
-- Analytics: Most active rooms (top N) - count by room_id
-- =============================================================================
CREATE INDEX idx_messages_room_count
    ON messages (room_id);

-- =============================================================================
-- Optional: BRIN index for very large append-only tables
-- Uncomment if table grows to millions and time-range queries slow down
-- BRIN is small and good for ordered timestamp data
-- =============================================================================
-- CREATE INDEX idx_messages_brin_time ON messages USING BRIN (timestamp_utc);
