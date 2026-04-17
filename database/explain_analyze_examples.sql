-- EXPLAIN ANALYZE templates for Assignment 3 / chat persistence tuning.
-- Run against chatdb after representative data load. Schema: 01_schema.sql (table `messages`).

-- Room timeline
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT message_id, room_id, user_id, username, message, message_type, timestamp_utc, server_id, client_ip, created_at
FROM messages
WHERE room_id = '1'
ORDER BY created_at DESC
LIMIT 100;

-- Analytics-style aggregation by room
EXPLAIN (ANALYZE, BUFFERS)
SELECT room_id, COUNT(*) AS cnt
FROM messages
GROUP BY room_id
ORDER BY cnt DESC;

-- Point lookup by message_id (primary key)
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM messages
WHERE message_id = 'replace-with-sample-id';
