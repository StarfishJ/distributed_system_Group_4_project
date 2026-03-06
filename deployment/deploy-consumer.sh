#!/bin/bash
# ============================================================
# Manual Deploy Consumer (nohup)
# Usage: ./deploy-consumer.sh <RABBITMQ_HOST>
# Example: ./deploy-consumer.sh 172.31.56.210
# ============================================================

set -e

RABBITMQ_HOST=${1:?Usage: ./deploy-consumer.sh <RABBITMQ_HOST>}
REPO_DIR=~/Distributed-System-Assignment-1
APP_DIR="$REPO_DIR/consumer"
LOG_FILE="/tmp/consumer.log"
JAR_FILE="target/chat-consumer-0.0.1-SNAPSHOT.jar"

echo "=== Updating Repository ==="
cd "$REPO_DIR"
git pull

echo "=== Building Consumer ==="
cd "$APP_DIR"
mvn clean package -DskipTests

echo "=== Stopping Existing Consumer ==="
# Find and kill the existing consumer process if running
PID=$(pgrep -f "chat-consumer-0.0.1-SNAPSHOT.jar") || true
if [ ! -z "$PID" ]; then
    echo "Killing existing consumer process (PID: $PID)..."
    kill $PID
    sleep 2
fi

echo "=== Starting Consumer (nohup) ==="
nohup java -Xmx1g \
    -Dspring.rabbitmq.host=${RABBITMQ_HOST} \
    -Dlogging.level.consumer=DEBUG \
    -Dlogging.level.org.springframework.amqp=WARN \
    -jar ${JAR_FILE} \
    > ${LOG_FILE} 2>&1 &

echo "=== Waiting for Startup ==="
sleep 8

echo "=== Checking Health ==="
curl http://localhost:8081/health

echo ""
echo "=== Deployment Complete ==="
echo "Logs: tail -f ${LOG_FILE}"
