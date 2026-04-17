locals {
  # EC2-based server-v2 + consumer-v3 (legacy). When false, use EKS for app workloads.
  create_app_ec2 = !var.enable_eks

  # Standalone RabbitMQ / Postgres EC2 (blank AMI). When enable_eks=true, default false so you
  # do not pay for duplicate infra if data plane runs in-cluster (k8s/rabbitmq.yaml, postgres.yaml, redis.yaml).
  create_ec2_rabbitmq_effective = var.use_amazon_mq ? false : (
    var.create_ec2_rabbitmq != null ? var.create_ec2_rabbitmq : !var.enable_eks
  )
  create_ec2_postgres_effective = var.use_rds_postgres ? false : (
    var.create_ec2_postgres != null ? var.create_ec2_postgres : !var.enable_eks
  )
}
