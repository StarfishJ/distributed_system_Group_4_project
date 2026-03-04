#!/bin/bash
# ============================================================
# Deploy Consumer as a systemd service (auto-restart on failure)
# Usage: sudo ./deploy-consumer.sh <RABBITMQ_HOST> [PORT]
# Example: sudo ./deploy-consumer.sh 10.0.1.50
# ============================================================

set -e

RABBITMQ_HOST=${1:?Usage: sudo ./deploy-consumer.sh <RABBITMQ_HOST> [PORT]}
PORT=${2:-8081}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/consumer"
JAR="$APP_DIR/target/chat-consumer-0.0.1-SNAPSHOT.jar"
SERVICE_NAME="chat-consumer"

echo "=== Deploying Consumer ==="
echo "  RabbitMQ Host: $RABBITMQ_HOST"
echo "  Consumer Port: $PORT"

# Build if JAR doesn't exist
if [ ! -f "$JAR" ]; then
    echo "[1/3] Building consumer..."
    cd "$APP_DIR"
    mvn clean package -DskipTests -q
    echo "[1/3] Build complete."
else
    echo "[1/3] JAR already exists, skipping build."
fi

# Create systemd service file
echo "[2/3] Creating systemd service..."
cat > /etc/systemd/system/${SERVICE_NAME}.service <<EOF
[Unit]
Description=CS6650 Chat Consumer
After=network.target

[Service]
Type=simple
User=$(whoami)
ExecStart=/usr/bin/java \\
    -Xmx512m \\
    -Dserver.port=${PORT} \\
    -Dspring.rabbitmq.host=${RABBITMQ_HOST} \\
    -Dspring.rabbitmq.port=5672 \\
    -Dspring.rabbitmq.username=guest \\
    -Dspring.rabbitmq.password=guest \\
    -Dlogging.level.consumer=INFO \\
    -jar ${JAR}
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

# Enable and start
echo "[3/3] Starting service..."
systemctl daemon-reload
systemctl enable ${SERVICE_NAME}
systemctl restart ${SERVICE_NAME}

echo ""
echo "=== Consumer Deployed ==="
echo "  Service:  ${SERVICE_NAME}"
echo "  Port:     ${PORT}"
echo "  Status:   systemctl status ${SERVICE_NAME}"
echo "  Logs:     journalctl -u ${SERVICE_NAME} -f"
echo "  Stop:     systemctl stop ${SERVICE_NAME}"
echo "  Health:   curl http://localhost:${PORT}/health"
echo ""
echo "  Auto-restart on failure: ENABLED (RestartSec=5s)"
