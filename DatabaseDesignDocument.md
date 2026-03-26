# CS6650 Assignment 3 — Database Design Document

## 1. Database choice justification

**PostgreSQL (14+)** is used for message persistence.

| Factor | Rationale |
|--------|-----------|
| **ACID + WAL** | Durable, transactional writes; fits chat history that must survive crashes. |
| **Batch inserts** | JDBC `rewriteBatchedInserts=true` rewrites batches to multi-row `INSERT`, matching our write-behind consumer. |
| **Indexing** | B-tree composite indexes align with Core queries (room+time, user+time, time+user). |
| **Ops** | `docker-compose` locally; `pg_dump` / PITR; portable to RDS/Aurora. |

## 2. Complete schema design

**Table `messages`** (single hot table for Assignment 3):

| Column | Type | Role |
|--------|------|------|
| `message_id` | `VARCHAR(64)` PK | Client UUID; idempotency via `ON CONFLICT DO NOTHING` |
| `room_id`, `user_id` | `VARCHAR(64)` | Sharding keys / query filters |
| `username`, `message`, `message_type` | text / varchar | Payload |
| `timestamp_raw` | `VARCHAR(64)` | Original client string |
| `timestamp_utc` | `TIMESTAMPTZ` NOT NULL | Parsed time for range queries |
| `server_id`, `client_ip` | optional | Traceability |
| `created_at` | `TIMESTAMPTZ` | Ingest time |

DDL: `database/01_schema.sql`. Materialized views: `database/03_views.sql` (`mv_user_rooms`, `mv_messages_per_minute`, `mv_user_activity`, `mv_room_activity`).

## 3. Indexing strategy

Defined in `database/02_indexes.sql`:

| Index | Columns | Serves |
|-------|---------|--------|
| `idx_messages_room_time` | `(room_id, timestamp_utc)` | Core 1 — room timeline |
| `idx_messages_user_time` | `(user_id, timestamp_utc)` | Core 2 — user history |
| `idx_messages_timestamp_user` | `(timestamp_utc, user_id)` | Core 3 — distinct users in window |
| `idx_messages_user_room_time` | `(user_id, room_id, timestamp_utc DESC)` | Core 4 fallback / grouping |
| `idx_messages_room_count` | `(room_id)` | Analytics helper |

**Trade-off:** five indexes increase write amplification; we rely on **materialized views** for heavy analytics. **`mv_user_rooms`** has a **unique** index on `(user_id, room_id)` for `REFRESH … CONCURRENTLY`. The server may use a short **Caffeine** cache on `/metrics` (TTL `server.metrics.*`) to avoid repeated scans.

## 4. Scaling considerations

- **Reads:** replicas for reporting; primary for writes.  
- **Growth:** time partitioning on `timestamp_utc` (monthly) when row count explodes.  
- **Sharding:** by `room_id` or hash if one node saturates.  
- **Pool:** HikariCP (consumer `max=10`, `min=2`); bounded queue before DB (`consumer.max-db-write-queue`) for backpressure.

## 5. Backup and recovery

**Logical:** `pg_dump -Fc chatdb` / `pg_restore`. **PITR:** WAL archive + `recovery_target_time`. **Docker/EC2:** named volume or EBS snapshot for `postgres_data`.