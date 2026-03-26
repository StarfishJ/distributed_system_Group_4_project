# Load Balanced Performance: Scaling & ALB Distribution Notes

## 1. No Throughput Improvement with Scaling (1–4 Servers)

**Observation**: Throughput stayed flat at ~3,500 msg/s across 1–4 servers, with RabbitMQ at only 2–3% CPU.

**Root Cause**: The **Consumer is the bottleneck**, not the servers. Downstream batching (50–100ms, 100–1000 messages per flush) means:
- The single Consumer instance processes messages at a bounded rate
- RabbitMQ is underutilized because the Consumer batches aggressively
- Adding more Server nodes does not increase Consumer capacity

**Proposed Solutions**:
- **Multiple Consumer instances**: Run 2–4 Consumer nodes, each handling a subset of rooms (e.g. consumer.rooms=1-5, 6-10, 11-15, 16-20)
- **Reduce batch size / interval**: Smaller batches = more frequent flushes = potentially higher throughput, at cost of more network/DB round-trips
- **Consumer parallelism**: Increase DB writer threads (with careful design) or add more consumer processes

---

## 2. Uneven ALB Distribution (42 vs 20 Channels)

**Observation**: 2-node test shows channel counts of 42 vs 20, suggesting imbalanced load.

**Root Cause**:
- **Sticky sessions (AWSALB cookie)**: Clients that connect first tend to stay on the same server
- **Connection timing**: Workers connect in staggered batches; earlier batches may land on one server
- **WebSocket long-lived connections**: Once established, sessions stick to one target

**Proposed Solutions**:
- **Disable stickiness for load-test**: For throughput benchmarking, disable target group stickiness to distribute connections evenly
- **Connection stagger tuning**: Spread worker connections over time and across multiple "rounds" to hit different ALB targets
- **Client-side**: Run multiple client processes, each from a different "start time", to naturally distribute across targets
- **Accept for production**: For chat, stickiness is often desirable (message ordering, connection locality). The imbalance may be acceptable; document it in the report.
