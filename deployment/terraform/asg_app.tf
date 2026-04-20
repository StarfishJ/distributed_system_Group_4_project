# -----------------------------------------------------------------------------
# App tier: Auto Scaling Groups + S3 bootstrap (server-v2, consumer-v3)
# deploy-ec2.ps1 uploads JAR + *.env to S3 and runs start-instance-refresh.
# -----------------------------------------------------------------------------

locals {
  app_artifacts_s3_prefix = "deploy/${replace(var.project_name, "_", "-")}"
  app_artifacts_bucket_name = lower(
    "${replace(var.project_name, "_", "-")}-app-${data.aws_caller_identity.current.account_id}-${replace(var.aws_region, "-", "")}"
  )
}

resource "aws_s3_bucket" "app_artifacts" {
  count  = local.create_asg_s3_app ? 1 : 0
  bucket = local.app_artifacts_bucket_name

  tags = { Name = "${var.project_name}-app-artifacts" }
}

resource "aws_s3_bucket_public_access_block" "app_artifacts" {
  count = local.create_asg_s3_app ? 1 : 0

  bucket = aws_s3_bucket.app_artifacts[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "app_artifacts" {
  count = local.create_asg_s3_app ? 1 : 0

  bucket = aws_s3_bucket.app_artifacts[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "app_asg_ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app_asg" {
  count = local.create_asg_s3_app ? 1 : 0

  name               = "${var.project_name}-asg-ec2"
  assume_role_policy = data.aws_iam_policy_document.app_asg_ec2_assume.json

  tags = { Name = "${var.project_name}-asg-ec2" }
}

data "aws_iam_policy_document" "app_asg_s3_read" {
  count = local.create_asg_s3_app ? 1 : 0

  statement {
    sid    = "ReadArtifacts"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
    ]
    resources = ["${aws_s3_bucket.app_artifacts[0].arn}/${local.app_artifacts_s3_prefix}/*"]
  }

  statement {
    sid       = "ListPrefix"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.app_artifacts[0].arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.app_artifacts_s3_prefix}/*"]
    }
  }
}

resource "aws_iam_role_policy" "app_asg_s3" {
  count = local.create_asg_s3_app ? 1 : 0

  name   = "${var.project_name}-asg-s3-read"
  role   = aws_iam_role.app_asg[0].id
  policy = data.aws_iam_policy_document.app_asg_s3_read[0].json
}

resource "aws_iam_instance_profile" "app_asg" {
  count = local.create_asg_s3_app ? 1 : 0

  name = "${var.project_name}-asg-profile"
  role = aws_iam_role.app_asg[0].name
}

resource "aws_launch_template" "server" {
  count = local.create_asg_s3_app ? 1 : 0

  name_prefix   = "${var.project_name}-srv-"
  image_id      = data.aws_ami.al2023.id
  instance_type = var.instance_type_server
  key_name      = local.ec2_key_name

  iam_instance_profile {
    name = aws_iam_instance_profile.app_asg[0].name
  }

  user_data = base64encode(templatefile("${path.module}/templates/server-asg-user-data.sh.tftpl", {
    bucket = aws_s3_bucket.app_artifacts[0].id
    prefix = local.app_artifacts_s3_prefix
    region = var.aws_region
  }))

  network_interfaces {
    associate_public_ip_address = true
    device_index                = 0
    security_groups             = [aws_security_group.internal.id]
  }

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name      = "${var.project_name}-server"
      Role      = "server-v2"
      CS6650ASG = "server"
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags          = { Name = "${var.project_name}-server" }
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_launch_template" "consumer" {
  count = local.create_asg_s3_app ? 1 : 0

  name_prefix   = "${var.project_name}-cons-"
  image_id      = data.aws_ami.al2023.id
  instance_type = var.instance_type_consumer
  key_name      = local.ec2_key_name

  iam_instance_profile {
    name = aws_iam_instance_profile.app_asg[0].name
  }

  user_data = base64encode(templatefile("${path.module}/templates/consumer-asg-user-data.sh.tftpl", {
    bucket = aws_s3_bucket.app_artifacts[0].id
    prefix = local.app_artifacts_s3_prefix
    region = var.aws_region
  }))

  network_interfaces {
    associate_public_ip_address = true
    device_index                = 0
    security_groups             = [aws_security_group.internal.id]
  }

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name      = "${var.project_name}-consumer"
      Role      = "consumer-v3"
      CS6650ASG = "consumer"
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags          = { Name = "${var.project_name}-consumer" }
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_autoscaling_group" "server" {
  count = local.create_asg_s3_app ? 1 : 0

  name                      = "${var.project_name}-server-asg"
  min_size                  = var.server_count
  max_size                  = var.server_count
  desired_capacity          = var.server_count
  health_check_type         = var.enable_alb ? "ELB" : "EC2"
  health_check_grace_period = var.asg_health_check_grace_period
  vpc_zone_identifier       = aws_subnet.public[*].id

  launch_template {
    id      = aws_launch_template.server[0].id
    version = "$Latest"
  }

  tag {
    key                 = "Name"
    value               = "${var.project_name}-server-asg"
    propagate_at_launch = true
  }
  tag {
    key                 = "Role"
    value               = "server-v2"
    propagate_at_launch = true
  }
  tag {
    key                 = "CS6650ASG"
    value               = "server"
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
  }

  depends_on = [aws_s3_bucket_server_side_encryption_configuration.app_artifacts]
}

resource "aws_autoscaling_group" "consumer" {
  count = local.create_asg_s3_app ? 1 : 0

  name                      = "${var.project_name}-consumer-asg"
  min_size                  = var.consumer_count
  max_size                  = var.consumer_count
  desired_capacity          = var.consumer_count
  health_check_type         = var.enable_alb ? "ELB" : "EC2"
  health_check_grace_period = var.asg_health_check_grace_period
  vpc_zone_identifier       = aws_subnet.public[*].id

  launch_template {
    id      = aws_launch_template.consumer[0].id
    version = "$Latest"
  }

  tag {
    key                 = "Name"
    value               = "${var.project_name}-consumer-asg"
    propagate_at_launch = true
  }
  tag {
    key                 = "Role"
    value               = "consumer-v3"
    propagate_at_launch = true
  }
  tag {
    key                 = "CS6650ASG"
    value               = "consumer"
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
  }

  depends_on = [aws_s3_bucket_server_side_encryption_configuration.app_artifacts]
}

resource "aws_autoscaling_attachment" "server_to_alb" {
  count = local.create_asg_s3_app && var.enable_alb ? 1 : 0

  autoscaling_group_name = aws_autoscaling_group.server[0].name
  lb_target_group_arn    = aws_lb_target_group.servers[0].arn
}

resource "aws_autoscaling_attachment" "consumer_to_alb" {
  count = local.create_asg_s3_app && var.enable_alb ? 1 : 0

  autoscaling_group_name = aws_autoscaling_group.consumer[0].name
  lb_target_group_arn    = aws_lb_target_group.consumers[0].arn
}

data "aws_instances" "server_asg" {
  count = local.create_asg_s3_app ? 1 : 0

  filter {
    name   = "tag:CS6650ASG"
    values = ["server"]
  }
  filter {
    name   = "instance-state-name"
    values = ["pending", "running"]
  }
}

data "aws_instances" "consumer_asg" {
  count = local.create_asg_s3_app ? 1 : 0

  filter {
    name   = "tag:CS6650ASG"
    values = ["consumer"]
  }
  filter {
    name   = "instance-state-name"
    values = ["pending", "running"]
  }
}
