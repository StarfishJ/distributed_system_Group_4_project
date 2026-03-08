# CS6650 Assignment 2 - Monitoring

The system leverages industry-standard monitoring tools already integrated into our architecture.

## 1. RabbitMQ Management Console
The primary source of truth for queue health and message throughput.
- **URL**: `http://<RMQ_IP>:15672`
- **Metrics**: 
  - Real-time publish/consume rates per queue.
  - Queue depth trends (target < 1000).
  - Active consumer counts (confirming 20 threads per room).

## 2. Spring Boot Actuator
Every Server and Consumer node exposes health and performance metrics via REST.
- **Health Check**: `http://<INSTANCE_IP>:8080/health` (Used by AWS ALB)
- **Application Metrics**: `http://<INSTANCE_IP>:8080/actuator/metrics`

## 3. AWS CloudWatch
Used for infrastructure-level monitoring during multi-node testing.
- **ALB Metrics**: Request count per target, 4xx/5xx error rates, and target response time.
- **EC2 Metrics**: CPU utilization per instance to verify even load distribution.
