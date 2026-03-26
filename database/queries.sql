-- CS6650 Assignment 3: Reference Queries for Core & Analytics
-- Use these in your Metrics API implementation

-- =============================================================================
-- CORE QUERY 1: Get messages for a room in time range
-- Input: roomId, startTime, endTime (use $1, $2, $3 for prepared statement)
-- =============================================================================
-- SELECT * FROM messages
-- WHERE room_id = $1 AND timestamp_utc BETWEEN $2 AND $3
-- ORDER BY timestamp_utc ASC;

-- =============================================================================
-- CORE QUERY 2: Get user's message history
-- Input: userId, optional startTime, endTime
-- =============================================================================
-- SELECT * FROM messages
-- WHERE user_id = $1
--   AND ($2::timestamptz IS NULL OR timestamp_utc >= $2)
--   AND ($3::timestamptz IS NULL OR timestamp_utc <= $3)
-- ORDER BY timestamp_utc ASC;

-- =============================================================================
-- CORE QUERY 3: Count active users in time window
-- Input: startTime, endTime
-- =============================================================================
-- SELECT COUNT(DISTINCT user_id) FROM messages
-- WHERE timestamp_utc BETWEEN $1 AND $2;

-- =============================================================================
-- CORE QUERY 4: Get rooms user has participated in (use materialized view)
-- Input: userId
-- =============================================================================
-- SELECT room_id, last_activity FROM mv_user_rooms WHERE user_id = $1 ORDER BY last_activity DESC;

-- =============================================================================
-- ANALYTICS 1: Messages per second/minute
-- =============================================================================
-- Per minute (from materialized view):
-- SELECT minute_bucket, message_count FROM mv_messages_per_minute ORDER BY minute_bucket;

-- Per second (real-time from table):
-- SELECT date_trunc('second', timestamp_utc) AS second_bucket, COUNT(*) AS cnt
-- FROM messages WHERE timestamp_utc >= $1 AND timestamp_utc < $2
-- GROUP BY date_trunc('second', timestamp_utc) ORDER BY second_bucket;

-- =============================================================================
-- ANALYTICS 2: Most active users (top N)
-- =============================================================================
-- SELECT user_id, message_count FROM mv_user_activity ORDER BY message_count DESC LIMIT $1;

-- =============================================================================
-- ANALYTICS 3: Most active rooms (top N)
-- =============================================================================
-- SELECT room_id, message_count FROM mv_room_activity ORDER BY message_count DESC LIMIT $1;

-- =============================================================================
-- ANALYTICS 4: User participation patterns (users per room, distribution)
-- =============================================================================
-- SELECT room_id, COUNT(DISTINCT user_id) AS unique_users, COUNT(*) AS total_messages
-- FROM messages GROUP BY room_id ORDER BY total_messages DESC;
