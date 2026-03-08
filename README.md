# CS6650 Assignment 2: Distributed WebSocket Chat System

This repository contains the enhanced implementation for Assignment 2, featuring a distributed architecture with RabbitMQ, an ALB-ready server, a standalone consumer, and comprehensive monitoring.

## Repository Structure

```
/
|-- server-v2/           # Updated Server with Queue integration & Broadcast logic
|-- consumer/            # Standalone Consumer (RabbitMQ -> Broadcast Fanout)
|-- client/
|   |-- client_part2/    # Load testing client with latency/throughput analysis
|-- monitoring/          # Monitoring documentation (RabbitMQ + Actuator)
|-- deployment/          # systemd service files and ALB configuration notes
|-- results/             # Test results and CSV exports
```

## Quick Start: Common Commands

### 1. Build All Modules
```bash
# Build Server, Consumer, and Client
mvn clean package -DskipTests -f server-v2/pom.xml
mvn clean package -DskipTests -f consumer/pom.xml
mvn clean package -DskipTests -f client/client_part2/pom.xml
```

### 2. Run Server-v2 (EC2/Local)
```bash
# Local (RabbitMQ on localhost)
java -Dserver.id=Node-1 -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar

# EC2 (Connect to RabbitMQ instance)
java -Dserver.id=EC2-Node-A -Dspring.rabbitmq.host=<RABBITMQ_IP> -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar
```

### 3. Run Consumer
```bash
# Local
java -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar

# EC2
java -Dspring.rabbitmq.host=<RABBITMQ_IP> -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar
```

### 4. System Monitoring
Please refer to [monitoring/README.md](./monitoring/README.md) for details on using the RabbitMQ Management Dashboard and Spring Actuator metrics.

### 5. Run Load Test (to ALB)
```bash
# Replace with your ALB DNS name
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://<ALB_DNS_NAME> 100000
```

## Verification & Health
- **Health Check**: `GET http://<INSTANCE_IP>:8080/health`
- **Metrics**: `GET http://<INSTANCE_IP>:8080/actuator/metrics`
- **RabbitMQ Dashboard**: `http://<RMQ_IP>:15672` (guest/guest)

## EC2 Deployment Workflow

Since the code is already pushed to GitHub, follow these steps to deploy to your AWS instances:

### 1. SSH into the Instance
```bash
ssh -i "your-key.pem" ec2-user@<INSTANCE_PUBLIC_IP>
```

### 2. Update and Deploy Server-v2 (Run on all 4 Server Nodes)
```bash
cd ~/Distributed-System-Assignment-1
git pull
# Build
mvn clean package -DskipTests -f server-v2/pom.xml
# Run (Replace <MQ_IP> with your RabbitMQ Private IP)
nohup java -Xmx1g \
  -Dserver.id=Node-1 \
  -Dspring.rabbitmq.host=<MQ_IP> \
  -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar \
  > ~/server.log 2>&1 &
```

### 3. Update and Deploy Consumer (Run on Consumer Node)
```bash
cd ~/Distributed-System-Assignment-1
git pull
# Build
mvn clean package -DskipTests -f consumer/pom.xml
# Run (Replace <MQ_IP> with your RabbitMQ Private IP)
nohup java -Xmx2g \
  -Dspring.rabbitmq.listener.simple.concurrency=20 \
  -Dspring.rabbitmq.listener.simple.prefetch=1 \
  -Dspring.rabbitmq.host=<MQ_IP> \
  -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar \
  > /tmp/consumer.log 2>&1 &
```

### 4. Verify on EC2
- Check logs: `tail -f /tmp/consumer.log` or `tail -f ~/server.log`
- Check health: `curl http://localhost:8080/health`

---

## Configuration
- **Server**: `server-v2/src/main/resources/application.properties`
- **Consumer**: `consumer/src/main/resources/application.properties`
- **Client**: `client/client_part2/src/main/resources/client.properties`

---

## Submission Artifacts
- **Architecture**: [DesignDocument.md](./DesignDocument.md)
- **Deployment Guide**: [deployment/alb-setup.md](./deployment/alb-setup.md)
- **Monitoring**: [monitoring/README.md](./monitoring/README.md)
