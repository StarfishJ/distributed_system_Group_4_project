# CS6650 Assignment 2: Distributed WebSocket Chat System

This repository contains the enhanced implementation for Assignment 2, featuring a distributed architecture with RabbitMQ, an ALB-ready server, a standalone consumer, and comprehensive monitoring.

## Repository Structure

```
/
|-- server-v2/           # Updated Server with Queue integration & Broadcast logic
|-- consumer/            # Standalone Consumer (RabbitMQ -> Broadcast Fanout)
|-- client/
|   |-- client_part2/    # Load testing client with latency/throughput analysis
|-- monitoring/          # Python monitoring script (RabbitMQ + App Metrics)
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
java -Dserver.id=EC2-Node-A -Dspring.rabbitmq.host=<RABBITMQ_IP> -jar chat-server.jar
```

### 3. Run Consumer
```bash
# Local
java -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar

# EC2
java -Dspring.rabbitmq.host=<RABBITMQ_IP> -jar chat-consumer.jar
```

### 4. Run Monitoring Script
```bash
# Monitor RabbitMQ and App nodes (Server/Consumer)
python monitoring/monitor.py --rabbitmq-host <RMQ_IP> --server-hosts <SRV_IP1>,<SRV_IP2> --consumer-hosts <CON_IP>
```

### 5. Run Load Test (to ALB)
```bash
# Replace with your ALB DNS name
java -jar client/client_part2/target/client_part2-1.0-SNAPSHOT.jar http://<ALB_DNS_NAME> 100000
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
sudo ./deployment/deploy-server.sh <RABBITMQ_PRIVATE_IP>
```
*Note: This script handles building, creating a systemd service, and starting the server.*

### 3. Update and Deploy Consumer (Run on Consumer Node)
```bash
cd ~/Distributed-System-Assignment-1
git pull
./deployment/deploy-consumer.sh <RABBITMQ_PRIVATE_IP>
```
*Note: This script kills the old process and starts a new one with `nohup`.*

### 4. Verify on EC2
- Check logs: `tail -f /tmp/consumer.log` or `journalctl -u chat-server -f`
- Check health: `curl http://localhost:8080/health`

---

## 🛠 Manual Deployment Cheat Sheet (No Scripts)

If you prefer manual commands due to changing IPs, use these (run after `mvn clean package`):

### **Server-v2 (Run on 4 Nodes)**
Copy-paste this (replace `<MQ_IP>`):
```bash
nohup java -Xmx1g \
  -Dserver.id=Node-1 \
  -Dspring.rabbitmq.host=<RABBITMQ_PRIVATE_IP_HERE> \
  -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar \
  > ~/server.log 2>&1 &
```
*(Change `Node-1` to 2, 3, 4 for each instance)*

### **Consumer (Run on 1 Node)**
Copy-paste this (replace `<MQ_IP>`):
```bash
nohup java -Xmx2g \
  -Dconcurrency=120 -Dmax-concurrency=120 -Dprefetch=5 \
  -Dspring.rabbitmq.host=<RABBITMQ_PRIVATE_IP_HERE> \
  -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar \
  > /tmp/consumer.log 2>&1 &
```

---

## Configuration
- **Server**: `server-v2/src/main/resources/application.properties`
- **Consumer**: `consumer/src/main/resources/application.properties`
- **Client**: `client/client_part2/src/main/resources/client.properties`

---

## Submission Artifacts
- **Architecture**: [system_design.md](file:///C:/Users/james/.gemini/antigravity/brain/f523feaa-6e29-49ea-90ce-d6608463293e/system_design.md)
- **Deployment Guide**: [alb_testing_guide.md](file:///C:/Users/james/.gemini/antigravity/brain/f523feaa-6e29-49ea-90ce-d6608463293e/alb_testing_guide.md)
- **Final Report**: [walkthrough.md](file:///C:/Users/james/.gemini/antigravity/brain/f523feaa-6e29-49ea-90ce-d6608463293e/walkthrough.md)
