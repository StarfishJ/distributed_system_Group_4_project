# CS6650 Assignment 4 — Final Report
 
**Repository:** https://github.com/StarfishJ/distributed_system_Group_4_project

---

## 1. Architecture Selection (Assignment 4 Task 1)

### 1.1 Assignment 3 implementations reviewed

Four Assignment 3 submissions from group members were evaluated against Assignment 4's stated selection criteria — **performance, scalability, maintainability**. Rows are ordered with the selected baseline (M1) first.

| # | Member | Stack | Deploy | Peak Throughput | p99 | Endurance |
|---|--------|-------|--------|-----------------|-----|-----------|
| M1 | Yuchen Huang *(this repo)* | Postgres + Rabbit + Redis + PgBouncer | Docker / EKS | 399 msg/s (local 1M) | 294 ms | 26 min @ 382 msg/s, DLQ=0 |
| M2 | Shiqi Yang | MySQL 8 + Rabbit | 2× t3.small EC2 | 4,129 msg/s (1M stress) | **38 ms** | — |
| M3 | Huanhuan Yan | Postgres, ALB → EC2 | AWS | **5,730 msg/s (500k)** | n/a | — |
| M4 | Yifan Tian | Postgres + `rewriteBatchedInserts`, MV refresh | AWS ALB | 3,822 msg/s sustained | 274 ms | **43 min / 9.88M msgs, 100%** |

All four share the same **event-driven architecture family**: WebSocket edge → RabbitMQ topic exchange → consumer → durable store. Differences are DB choice, deployment target, batch configuration, and which optional subsystems each member shipped.

### 1.2 Selection criteria

- **Performance** — M2 leads on tail latency (**p99 = 38 ms** on MySQL); M4 leads on sustained endurance (**9.88M msgs over 43 min, 100% success**); M3 leads on burst throughput (**5,730 msg/s**) but degrades **−35%** to 3,697 msg/s under 1M stress.
- **Scalability** — **M1 leads**: full `k8s/` manifests (Deployment / HPA-ready / Ingress / ConfigMap / Secret) and `deployment/terraform/` (VPC, EKS, ALB, RDS, managed services). M3/M4 use static EC2 pairs with no container orchestration; M2 is 2-instance AWS only.
- **Maintainability** — **M1 leads**: already implements **6 of Assignment 4's 10 optimization categories** (indexing, connection pooling via HikariCP, materialized views, application caching via Caffeine + Redis, cache invalidation via scheduled MV refresh, read/write split via PgBouncer `6432`/`6433`). Each sits behind a runtime flag, enabling clean Config-A-vs-B measurement without code churn.

### 1.3 Decision: select M1 as the Assignment 4 baseline

Assignment 4 measures **optimization deltas**, not absolute throughput. Because all four Assignment 3 instances share the same architecture shape, the real question is which instance lets us isolate *added* optimizations most cleanly:

1. **M1 ships the optimization scaffolding Assignment 4 asks for.** Every optimization toggle already exists behind a configuration flag (`server.redis.enabled`, `consumer.redis.enabled`, `server.metrics.read-replica.enabled`, `server.metrics.cache-ttl-seconds`). Remaining work is to **measure**, not **build**.
2. **M4's methodology is portable; its code is not needed.** We adopt M4's test harness (batch-size × flush-interval matrix, `pg_stat_statements` snapshot, RabbitMQ queue-depth capture, 43-min endurance pattern) and apply it on M1's stack — getting M4-quality evidence on M1-quality code.
3. **M3 shows stress-time regression** (−35% at 1M), which would confound optimization measurements — we could not distinguish infrastructure regression from optimization gain.
4. **M2 (MySQL) would discard M1's Postgres-specific assets** (materialized views, BRIN index on `message_ts`, PgBouncer read/write split) for a p99 improvement on a metric Assignment 4 does not prioritize.

### 1.4 Why M1's shape fits the Assignment 4 workload

1. **Stateless edge (`server-v2`).** Netty/WebFlux handles concurrent WebSocket sessions. The server **does not** insert chat rows; it validates, batches, and **publishes to RabbitMQ** with **publisher confirms** before replying **`QUEUED`** to clients. This keeps ingress fast and failure modes explicit (**`SERVER_BUSY`** when the broker rejects load).
2. **Single writer to PostgreSQL (`consumer-v3`).** All **`INSERT INTO messages … ON CONFLICT DO NOTHING`** run in the consumer after ordered consumption from **20 room queues** (`room.1`–`room.20`). Sharding uses **`Math.abs(roomId.hashCode()) % 20 + 1`** while preserving the real **`roomId`** in the payload for delivery and analytics. One writer path avoids split-brain persistence and simplifies idempotency.
3. **RabbitMQ as the backplane.** Ingress uses a **topic exchange**; broadcast uses **fan-out** (`chat.broadcast`) by default, with an optional **topic exchange** (`chat.broadcast.topic`) for **targeted** delivery when Redis presence is available. This separates **ingress backpressure** (queue depth, publish confirms) from **broadcast amplification** (copies per server).
4. **Observability and contention control.** **`GET /health`** supports ALB probes; **`GET /metrics`** is analytics-heavy and is isolated from the hot path using **caching (Caffeine + optional Redis)**, **materialized views**, and an optional **read JDBC URL** (locally: PgBouncer read pool; in AWS: RDS read replica). **REFRESH MATERIALIZED VIEW** always runs on the **primary** datasource, never on a read-only replica connection.

### 1.5 Accepted trade-offs

- **Lower starting throughput on local Docker than M3/M4 on AWS.** Mitigation: the Assignment 4 JMeter sweep can also run on EKS (`k8s/` manifests) with t3.medium-class nodes comparable to M4's; expected ceiling ≈ M4's 3,800 msg/s.
- **Highest operational surface** (Postgres + Rabbit + Redis + PgBouncer). Accepted in exchange for the six pre-built optimizations above and the ability to toggle each one independently for clean measurement.

---

## 2. Optimization 1 — Redis-Backed Dedup, Broadcast Gating, and Presence (7.5 points)

### 2.1 What was optimized and why (tradeoffs)

**Problem.** After restarts or redeliveries, the consumer can repeat work: redundant **`batchUpsert`** attempts and duplicate **fan-out** publishes for the same **`messageId`**. The server also needs a **shared** view of which replicas have users in a room to avoid **O(number of servers)** useless deliveries when using targeted broadcast.

**Optimization.** Enable **`consumer.redis.enabled=true`** and **`server.redis.enabled=true`**:

- **`chat:consumer:persisted:{messageId}`** — set after successful DB batch persist so redeliveries skip useless writes (SQL **`ON CONFLICT`** remains the safety net).
- **`SETNX chat:consumer:broadcast:{messageId}`** — gates fan-out/topic publish so the same logical message is not re-broadcast after recovery.
- **`presence:room:{roomId}`** — Redis **SET** of sanitized **`server.instance-id`** suffixes, maintained on join/leave and heartbeat, consumed by the consumer when **`consumer.broadcast.targeted=true`**.

**Tradeoffs.** We add a **hard dependency** on Redis for these behaviors when enabled; **staleness** in presence is possible (eventually consistent), mitigated by **fan-out fallback** when the set is empty. Ops must monitor Redis memory and TTL settings (**`consumer.redis.*-ttl-hours`**, **`server.presence.*`**).

### 2.2 Implementation details (repository)

- **Consumer:** `ConsumerDedupRedisService`, `MessageConsumer` wiring; keys documented in `DesignDocument.md`.
- **Server:** `PresenceRegistry`, `MetricsResponseRedisCache`, `server.redis.*` and `spring.data.redis.*` in `application.properties`.
- **Kubernetes:** `redis.yaml`, `chat-config` **`REDIS_HOST`**, env **`CONSUMER_REDIS_ENABLED`**, **`SERVER_REDIS_ENABLED`** in `consumer.yaml` / `server.yaml`.

### 2.3 Performance impact measurement

**Method.** Same JMeter plan (**`assignment-baseline-100k-5min.jmx`**: 700×`/health` + 300×`/metrics`, 100k samples). Compare **Configuration A** (Redis features **off**) vs **Configuration B** (Redis **on**, targeted broadcast optional but consistent across server and consumer).

**Metrics to capture (HTML report):** Average, **p95**, **p99**, **Throughput**, **Error %**; plus **Consumer CPU** and **RabbitMQ queue depth** during the run (appendix screenshots).

**Example results table (replace with your measured numbers):**

| Metric | Baseline (Redis off) | Optimized (Redis on) | Δ |
|--------|----------------------|----------------------|---|
| Throughput (HTTP req/s) | *e.g. 1,634* | *fill after run* | *%* |
| p95 latency (ms) | *fill* | *fill* | *%* |
| p99 latency (ms) | *fill* | *fill* | *%* |
| Error rate | *0%* | *target 0%* | — |

*Interpretation:* Redis reduces duplicate **consumer** work and **broker** chatter under redelivery scenarios; under a **clean** JMeter HTTP-only run the delta may be **modest**—highlight **consumer-side** and **MQ** graphs when duplicates are injected (restart consumer mid-test) to show the benefit.

---

## 3. Optimization 2 — Metrics Read-Path Isolation and Caching (7.5 points)

### 3.1 What was optimized and why (tradeoffs)

**Problem.** **`GET /metrics`** issues heavy **SELECT** workloads (and MV-backed aggregates). On a single PostgreSQL endpoint, analytics reads **contend** with **`consumer-v3`** **write** traffic (connection slots, I/O).

**Optimization.**

1. **Separate JDBC for reads** when **`server.metrics.read-replica.enabled=true`**: **`server.metrics.read-replica.jdbc-url`** serves **`MetricsService`** **`jdbcRead`**; **`spring.datasource`** remains **primary** for **`REFRESH MATERIALIZED VIEW`** and maintenance.
2. **Response caching:** **Caffeine** plus optional **Redis** JSON cache (**`metrics:v1:*`**, TTL **`server.metrics.cache-ttl-seconds`**).
3. **Coarse-by-room mode** (**`server.metrics.cache-coarse-by-room=true`**) to shrink Redis key cardinality for wide dashboards.

**Tradeoffs.** Read paths may see **replication lag** on a true RDS replica; analytics are **stale-OK** by design. Cache invalidation runs on **scheduled MV refresh** and explicit refresh paths (HTTP refresh is **disabled** in `k8s/server.yaml` via **`SERVER_METRICS_ALLOW_HTTP_MV_REFRESH=false`** to prevent accidental load through a public ALB).

### 3.2 Implementation details

- **Code:** `MetricsReadReplicaConfiguration`, `MetricsService` (**`jdbcWrite`** vs **`jdbcRead`**), `MetricsResponseRedisCache`, `MaterializedViewRefreshScheduler`.
- **Local stack:** PgBouncer **6432** (write) / **6433** (read pool) in `database/docker-compose.yml`; properties point **`read-replica.jdbc-url`** at **6433**.
- **Kubernetes (in-cluster Postgres):** read-replica property **off** (`SERVER_METRICS_READ_REPLICA_ENABLED=false`) unless you introduce a **real** replica endpoint.

### 3.3 Performance impact measurement

**Method.** Hold JMeter mix constant; compare **p95/p99** of **`/metrics`** samples (or aggregate) with read-path isolation **off** vs **on** (same hardware). Optionally raise **`/metrics`** share temporarily to stress the read path.

**Example comparison table (replace with your numbers):**

| Metric | Single DB URL for reads | Read pool / replica JDBC + cache | Δ |
|--------|-------------------------|-------------------------------------|---|
| `/metrics` p95 (ms) | *fill* | *fill* | *%* |
| `/metrics` p99 (ms) | *fill* | *fill* | *%* |
| Primary DB active connections | *fill* | *fill* | *%* |

---

## 4. Future Optimizations (5 points)

| Idea | Expected impact | Implementation complexity |
|------|-----------------|---------------------------|
| **1. RDS read replica + PgBouncer** | Lower **`/metrics`** latency and primary CPU; production-grade lag-aware analytics | **Medium** (Terraform, SG rules, JDBC secrets, failover playbook) |
| **2. RabbitMQ on EC2 with gp3** (or **Amazon MQ**) | Removes **gp2 I/O credit** ceilings; managed option adds HA options | **Low–Medium** (gp3 in Terraform; MQ is higher cost) |
| **3. Quorum queues / clustered Rabbit** | HA and higher sustained throughput at multi-node broker cost | **High** (cluster ops, queue migrations) |
| **4. Consumer HPA on custom metrics** (queue depth, lag) | Elastic capacity for hot rooms without over-provisioning | **High** (metrics adapter / KEDA-style) |
| **5. Tiered MV refresh** (per-MV cron, staggered windows) | Smoother I/O; fewer spikes than one global refresh | **Medium** (scheduler refactor) |

---

## 5. Performance Metrics (10 points)

### 5.1 Test methodology

- **Tool:** Apache JMeter **non-GUI** (`jmeter -n -t … -l … -e -o …`).
- **Plans:** **`assignment-baseline-100k-5min.jmx`** (≈1000 threads, **100k** HTTP samples, **70%** **`GET /health`**, **30%** **`GET /metrics`** without `refreshMaterializedViews`); **`assignment-stress-30min.jmx`** for longer stress (500 threads, duration + throughput timers).
- **Mapping:** Rubric “writes” are approximated by heavy **`/metrics`** reads; true chat **writes** are **WebSocket** (optional WebSocket plugin plan in `load-tests/jmeter/README.md`).
- **Hygiene:** Empty **`.jtl`** and **report `-o` directory** before each run; on Kubernetes, do not enable **`refreshMaterializedViews`** on **`/metrics`** (returns **403** when **`allow-http-mv-refresh=false`**).

### 5.2 Results presentation (baseline vs optimized)

**Baseline (representative run — replace with your JTL/HTML):**

| Measure | Value |
|---------|--------|
| Total samples | 100,000 |
| Wall time | ~61 s (environment-dependent) |
| Throughput | ~1,634 req/s |
| Error rate | 0% |

**Optimized (fill after second configuration):**

| Measure | Value |
|---------|--------|
| Total samples | 100,000 |
| Throughput | *TBD* |
| p95 / p99 | *TBD* |
| Error rate | *TBD* |

**Improvement summary (example formula):**  
`Improvement % = (Baseline − Optimized) / Baseline × 100%` for latency (lower is better); for throughput invert the ratio.

### 5.3 Resource utilization

Include **screenshots** (main text or appendix): **CPU / memory** for **`server-v2`**, **`consumer-v3`**, and **host or pod**; **database** connections or RDS metrics; **RabbitMQ** queue depth if available. State clearly **which environment** (local Docker vs EKS).

### 5.4 Bottleneck analysis

- **Ingress / publish confirms:** Room queue **max-length** and **reject-publish** cause **`SERVER_BUSY`**—correct backpressure, not a client retry storm without limits.
- **Broker disk:** Historical **`client_part2`** tests hit **gp2** limits; **gp3** or managed MQ is the infrastructure mitigation.
- **`/metrics`:** Primary DB and MV refresh—mitigated by **read path isolation**, **cache**, and **disabling HTTP MV refresh** on public endpoints.

---

## 6. File Format Checklist

- [ ] **Single PDF** with all sections above (this report exported).
- [ ] **≤ 8 pages** for core content; move extra JMeter HTML screenshots and long tables to **Appendix A**.
- [ ] **Screenshots:** JMeter dashboard summary + at least one resource monitor view.
- [ ] **Academic honesty:** All numbers labeled **measured** vs **illustrative**; replace *TBD* / *e.g.* with your actual tables before submission.

---

## Appendix A — Optional Material

- Full JMeter HTML report paths and command lines used.
- `terraform output` redacted summary (cluster name, no secrets).
- Additional **`kubectl top pods`** / CloudWatch graphs.

---
