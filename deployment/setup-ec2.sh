#!/bin/bash
# ============================================================
# EC2 Instance Setup Script
# Run this on a fresh Amazon Linux 2023 / Ubuntu 22.04 instance
# Usage: chmod +x setup-ec2.sh && ./setup-ec2.sh
# ============================================================

set -e

echo "=== CS6650 Assignment 2 - EC2 Setup ==="

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
fi

echo "[1/4] Installing Java 17..."
if [ "$OS" = "amzn" ]; then
    sudo yum install -y java-17-amazon-corretto-devel
elif [ "$OS" = "ubuntu" ]; then
    sudo apt-get update -y
    sudo apt-get install -y openjdk-17-jdk
else
    echo "Unsupported OS: $OS. Please install Java 17 manually."
    exit 1
fi

java -version
echo "[1/4] Java installed successfully."

echo "[2/4] Installing Maven..."
if [ "$OS" = "amzn" ]; then
    sudo yum install -y maven
elif [ "$OS" = "ubuntu" ]; then
    sudo apt-get install -y maven
fi

mvn -version
echo "[2/4] Maven installed successfully."

echo "[3/4] Installing monitoring tools..."
if [ "$OS" = "amzn" ]; then
    sudo yum install -y htop curl jq
elif [ "$OS" = "ubuntu" ]; then
    sudo apt-get install -y htop curl jq python3
fi
echo "[3/4] Monitoring tools installed."

echo "[4/4] Configuring system limits..."
# Increase file descriptor limits for high-concurrency WebSocket
echo "* soft nofile 65535" | sudo tee -a /etc/security/limits.conf
echo "* hard nofile 65535" | sudo tee -a /etc/security/limits.conf
echo "[4/4] System limits configured."

echo ""
echo "=== Setup Complete ==="
echo "Next steps:"
echo "  1. Clone your repo: git clone <your-repo-url>"
echo "  2. Build: cd server-v2 && mvn clean package -DskipTests"
echo "  3. Run: ./deploy-server.sh <RABBITMQ_HOST>"
