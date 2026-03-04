#!/bin/bash
# ============================================================
# RabbitMQ Setup Script
# Run this on the dedicated RabbitMQ EC2 instance.
# Usage: chmod +x setup-rabbitmq.sh && ./setup-rabbitmq.sh
# ============================================================

set -e

echo "=== Installing RabbitMQ ==="

# Install Erlang + RabbitMQ
if [ -f /etc/os-release ]; then
    . /etc/os-release
fi

if [ "$ID" = "amzn" ]; then
    # Amazon Linux 2023
    sudo yum install -y erlang rabbitmq-server
elif [ "$ID" = "ubuntu" ]; then
    # Ubuntu 22.04 - use official RabbitMQ repo
    sudo apt-get update -y
    sudo apt-get install -y curl gnupg apt-transport-https

    # Add RabbitMQ signing key and repository
    curl -1sLf "https://keys.openpgp.org/vks/v1/by-fingerprint/0A9AF2115F4687BD29803A206B73A36E6026DFCA" | sudo gpg --dearmor | sudo tee /usr/share/keyrings/com.rabbitmq.team.gpg > /dev/null
    sudo apt-get install -y rabbitmq-server
fi

# Enable and start RabbitMQ
sudo systemctl enable rabbitmq-server
sudo systemctl start rabbitmq-server

# Enable Management Console (port 15672)
sudo rabbitmq-plugins enable rabbitmq_management

# Create admin user (replace password in production!)
sudo rabbitmqctl add_user admin admin123
sudo rabbitmqctl set_user_tags admin administrator
sudo rabbitmqctl set_permissions -p / admin ".*" ".*" ".*"

echo ""
echo "=== RabbitMQ Installed ==="
echo "  AMQP Port:       5672"
echo "  Management UI:   http://<this-ip>:15672"
echo "  Username:        admin"
echo "  Password:        admin123"
echo ""
echo "IMPORTANT: Update your EC2 Security Group to allow:"
echo "  - Port 5672 (AMQP) from server/consumer instances"
echo "  - Port 15672 (Management UI) from your IP"
