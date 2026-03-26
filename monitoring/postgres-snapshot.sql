-- CS6650 Assignment 3 — optional DB health snapshot for Performance Report
-- Run: psql -U postgres -d chatdb -f monitoring/postgres-snapshot.sql
-- Paste relevant rows into your report (connections, cache hit, locks).

SELECT now() AS snapshot_time;

SELECT count(*) AS messages_rows FROM messages;

SELECT
    numbackends AS active_connections,
    xact_commit,
    xact_rollback,
    blks_read,
    blks_hit,
    CASE WHEN blks_hit + blks_read = 0 THEN NULL
         ELSE round(100.0 * blks_hit / (blks_hit + blks_read), 2)
    END AS buffer_hit_pct
FROM pg_stat_database
WHERE datname = current_database();

SELECT state, count(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY state;

SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables
WHERE relname = 'messages';
