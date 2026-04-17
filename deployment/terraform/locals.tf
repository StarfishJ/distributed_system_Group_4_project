locals {
  # EC2-based server-v2 + consumer-v3 (legacy). When false, use EKS for app workloads.
  create_app_ec2 = !var.enable_eks
}
