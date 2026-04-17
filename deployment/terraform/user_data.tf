locals {
  # Install Docker on any host that runs our stack EC2s (EC2 app path or standalone MQ/DB boxes).
  user_data_install_docker = local.create_app_ec2 || local.create_ec2_rabbitmq_effective || local.create_ec2_postgres_effective

  user_data_base = <<-EOT
    #!/bin/bash
    set -e
    dnf update -y
    dnf install -y java-17-amazon-corretto-headless git
%{if local.user_data_install_docker~}
    dnf install -y docker
    systemctl enable --now docker
    usermod -aG docker ec2-user || true
    ARCH=$(uname -m)
    case "$ARCH" in x86_64) C=x86_64 ;; aarch64) C=aarch64 ;; *) C=x86_64 ;; esac
    curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-$$C" -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
%{endif~}
  EOT
}
