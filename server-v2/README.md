# WebSocket Chat Server

Spring Boot WebFlux reactive WebSocket server: `/chat/{roomId}` + REST `GET /health`.

## Build

```bash
cd server
mvn clean package
```

This creates `target/chat-server-0.0.1-SNAPSHOT.jar`.

## Run Locally

```bash
# Option 1: Maven
mvn spring-boot:run

# Option 2: JAR
java -jar target/chat-server-0.0.1-SNAPSHOT.jar

# Option 3: With custom Netty worker threads
java -Dnetty.worker.threads=32 -jar target/chat-server-0.0.1-SNAPSHOT.jar
```

Server listens on **port 8080**.

## Deploy to EC2

### Prerequisites
- EC2 instance (Amazon Linux 2023 recommended)
- Java 17+ installed
- Security group allows inbound traffic on port 8080

### Steps

1. **Upload server files to EC2:**
   ```bash
   scp -i your-key.pem server-src.zip ec2-user@your-ec2-ip:~/
   ```

2. **SSH into EC2:**
   ```bash
   ssh -i your-key.pem ec2-user@your-ec2-ip
   ```

3. **Extract and build:**
   ```bash
   unzip -o server-src.zip
   cd server
   mvn clean package -DskipTests
   ```

4. **Run server in background:**
   ```bash
   nohup java -Dnetty.worker.threads=32 -jar target/chat-server-0.0.1-SNAPSHOT.jar > server.log 2>&1 &
   ```

5. **Verify server is running:**
   ```bash
   # Check process
   ps aux | grep java
   
   # Check health endpoint
   curl http://localhost:8080/health
   
   # View logs
   tail -f server.log
   ```

6. **Stop server:**
   ```bash
   pkill -f chat-server
   ```

## Configuration

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
# Netty worker threads can be set via -Dnetty.worker.threads=32
```

## Test

- **Health check:** `curl http://localhost:8080/health`
- **WebSocket test:** `wscat -c ws://localhost:8080/chat/1`

Example valid JSON to send over WebSocket:

```json
{"userId":"123","username":"user123","message":"hello","timestamp":"2025-01-29T12:00:00Z","messageType":"TEXT"}
```

Valid messages are echoed back with `serverTimestamp` and `status: "OK"`. Invalid messages return `status: "ERROR"` and an error `message`.

## Architecture

- **Framework:** Spring Boot WebFlux (Reactive)
- **WebSocket:** Reactor Netty
- **Threading:** Non-blocking I/O with configurable worker threads
- **Room Management:** In-memory `ConcurrentHashMap` tracking joined users per room
- **Message Validation:** Inline JSON parsing and validation (JOIN/TEXT/LEAVE logic)
