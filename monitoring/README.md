# CS6650 Assignment 2 - Monitoring Tools

## System Monitor (`monitor.py`)

A real-time monitoring script that collects metrics from RabbitMQ and application health endpoints.

### Usage

```bash
# Monitor RabbitMQ only
python3 monitor.py --rabbitmq-host 10.0.1.50

# Monitor RabbitMQ + Server + Consumer
python3 monitor.py \
    --rabbitmq-host 10.0.1.50 \
    --server-host 10.0.1.100 \
    --consumer-host 10.0.1.200 \
    --interval 5

# Custom credentials
python3 monitor.py \
    --rabbitmq-host 10.0.1.50 \
    --rabbitmq-user admin \
    --rabbitmq-password admin123
```

### Output

- **Console**: Live display of queue depths, publish/consume rates, and consumer counts
- **CSV** (`metrics.csv`): Timestamped metrics for graphing in your report

### Metrics Collected

| Metric | Source | Description |
|:---|:---|:---|
| Queue Depth | RabbitMQ API | Number of messages waiting in each queue |
| Publish Rate | RabbitMQ API | Messages/second being published |
| Consume Rate | RabbitMQ API | Messages/second being consumed |
| Consumer Count | RabbitMQ API | Active consumers per queue |
| Server Health | `/health` | Server-v2 status and metrics |
| Consumer Health | `/health` | Consumer status and throughput |

### Requirements

- Python 3.6+ (no external dependencies)
- RabbitMQ Management Plugin enabled (port 15672)

### Generating Graphs for Report

Use the CSV output to create charts. Example with Python matplotlib:

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("metrics.csv")
df["timestamp"] = pd.to_datetime(df["timestamp"])

# Plot queue depth over time
room_queues = df[df["queue_name"].str.startswith("room.")]
pivot = room_queues.pivot_table(index="timestamp", columns="queue_name", values="depth")
pivot.plot(figsize=(12, 6), title="Queue Depth Over Time")
plt.ylabel("Messages")
plt.savefig("queue_depth.png")
```
