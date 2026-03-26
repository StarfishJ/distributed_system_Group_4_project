output "alb_dns_name" {
  description = "Point load test client here (HTTP 80)"
  value       = var.enable_alb ? aws_lb.main[0].dns_name : null
}

# --- RabbitMQ: Amazon MQ OR self-hosted EC2 ---

output "rabbitmq_mode" {
  description = "amazon_mq | ec2"
  value       = var.use_amazon_mq ? "amazon_mq" : "ec2"
}

output "rabbitmq_host" {
  description = "spring.rabbitmq.host (VPC: use this IP or broker DNS from AWS console)"
  value       = var.use_amazon_mq ? aws_mq_broker.rabbitmq[0].instances[0].ip_address : aws_instance.rabbitmq[0].private_ip
}

output "rabbitmq_amqp_console_url" {
  description = "Amazon MQ management UI (open from browser via SSM port-forward or bastion; not public by default)"
  value       = var.use_amazon_mq ? aws_mq_broker.rabbitmq[0].instances[0].console_url : null
}

output "rabbitmq_username" {
  value = var.use_amazon_mq ? var.amazon_mq_username : "guest"
}

output "rabbitmq_password" {
  description = "Amazon MQ password (sensitive). Self-hosted EC2: null (configure RabbitMQ yourself)."
  value       = length(random_password.mq) > 0 ? random_password.mq[0].result : null
  sensitive   = true
}

output "rabbitmq_public_ip" {
  value = var.use_amazon_mq ? null : aws_instance.rabbitmq[0].public_ip
}

output "rabbitmq_private_ip" {
  value = var.use_amazon_mq ? null : aws_instance.rabbitmq[0].private_ip
}

# --- PostgreSQL: RDS OR self-hosted EC2 ---

output "postgres_mode" {
  description = "rds | ec2"
  value       = var.use_rds_postgres ? "rds" : "ec2"
}

output "postgres_jdbc_url" {
  description = "JDBC URL for server-v2 / consumer-v3 (replace PASSWORD with output postgres_password)"
  value       = var.use_rds_postgres ? "jdbc:postgresql://${aws_db_instance.postgres[0].address}:5432/${var.rds_database_name}" : "jdbc:postgresql://${aws_instance.postgres[0].private_ip}:5432/chatdb"
}

output "postgres_address" {
  value = var.use_rds_postgres ? aws_db_instance.postgres[0].address : aws_instance.postgres[0].private_ip
}

output "postgres_username" {
  value = var.use_rds_postgres ? var.rds_master_username : "chat"
}

output "postgres_password" {
  description = "RDS master password when use_rds_postgres=true"
  value       = length(random_password.rds) > 0 ? random_password.rds[0].result : null
  sensitive   = true
}

output "postgres_public_ip" {
  value = var.use_rds_postgres ? null : aws_instance.postgres[0].public_ip
}

output "postgres_private_ip" {
  value = var.use_rds_postgres ? null : aws_instance.postgres[0].private_ip
}

# --- App EC2 ---

output "server_public_ips" {
  value = aws_instance.server[*].public_ip
}

output "server_private_ips" {
  value = aws_instance.server[*].private_ip
}

output "consumer_public_ip" {
  value = aws_instance.consumer.public_ip
}

output "consumer_private_ip" {
  value = aws_instance.consumer.private_ip
}

output "ec2_key_pair_name" {
  description = "Key pair used by all instances (created by TF or pre-existing)"
  value       = local.ec2_key_name
}

output "ssh_hint" {
  value = "ssh -i ~/.ssh/<your-private-key> ec2-user@<public_ip_from_outputs>"
}
