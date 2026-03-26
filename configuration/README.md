# Configuration index (Assignment 3 submission)

All tunables are in version-controlled source. **Do not commit real passwords**; use env vars or local overrides.

| Area | File | What to cite in report |
|------|------|-------------------------|
| **PostgreSQL URL, Hikari pool** | `consumer-v3/src/main/resources/application.properties` | `spring.datasource.*`, `rewriteBatchedInserts` |
| **Batch write / flush** | same | `consumer.batch-size`, `consumer.flush-interval-ms`, retry / DLQ |
| **Consumer circuit breaker** | same | `consumer.circuit-breaker.*` |
| **Server DB pool** | `server-v2/src/main/resources/application.properties` | `spring.datasource.hikari.*` |
| **Netty / WebFlux threads** | `server-v2/src/main/java/server/NettyConfig.java` | Worker thread formula |
| **RabbitMQ + resilience4j (server)** | `server-v2/src/main/resources/application.properties` | `spring.rabbitmq.*`, `resilience4j.circuitbreaker.instances.rabbitmq.*` |
| **RabbitMQ (consumer-v3)** | `consumer-v3/src/main/resources/application.properties` | Listener concurrency, prefetch |
| **Metrics API cache** | `server-v2/src/main/resources/application.properties` | `server.metrics.cache-ttl-seconds`, `cache-max-keys` |
| **Load client workers / rooms** | `client/client_part2/src/main/resources/client.properties` | `mainThreads`, `numRooms`, `worker.batchSize` |
| **Database + Docker** | `database/docker-compose.yml`, `database/*.sql` | Ports, volumes, init order |

For a single printable bundle, copy the relevant sections from the above into your PDF appendix or link paths in the Performance Report.
