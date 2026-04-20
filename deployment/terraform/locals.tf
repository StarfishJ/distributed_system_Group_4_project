locals {
  # EC2-based server-v2 + consumer-v3 (legacy). When false, use EKS for app workloads.
  create_app_ec2 = !var.enable_eks

  # ASG + S3 + IAM instance profile (needs iam:CreateRole). If false: plain EC2 + SSH (Vocareum-friendly).
  create_asg_s3_app = var.enable_asg_s3_app_tier && local.create_app_ec2

  # Managed Redis for EC2 app tier (replaces Docker redis on Postgres/Rabbit hosts when true).
  create_elasticache_redis = var.use_elasticache_redis && local.create_app_ec2

  # Standalone RabbitMQ / Postgres EC2 (blank AMI). When enable_eks=true, default false so you
  # do not pay for duplicate infra if data plane runs in-cluster (k8s/rabbitmq.yaml, postgres.yaml, redis.yaml).
  create_ec2_rabbitmq_effective = var.use_amazon_mq ? false : (
    var.create_ec2_rabbitmq != null ? var.create_ec2_rabbitmq : !var.enable_eks
  )
  create_ec2_postgres_effective = var.use_rds_postgres ? false : (
    var.create_ec2_postgres != null ? var.create_ec2_postgres : !var.enable_eks
  )

  # JMeter load generator: only with EC2 app tier + ALB (same-region path to public ALB DNS).
  create_jmeter_ec2 = var.enable_jmeter_ec2 && local.create_app_ec2 && var.enable_alb
}
