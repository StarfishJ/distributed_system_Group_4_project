# Optional load-generator EC2 in the same region/VPC as the ALB (short RTT; avoids home-ISP TCP issues).
# Enable with enable_jmeter_ec2 = true (requires enable_eks = false, enable_alb = true).

resource "aws_instance" "jmeter" {
  count                  = local.create_jmeter_ec2 ? 1 : 0
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type_jmeter
  key_name               = local.ec2_key_name
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.internal.id]

  user_data = <<-EOT
    #!/bin/bash
    set -euxo pipefail
    dnf update -y
    dnf install -y java-17-amazon-corretto-headless wget tar gzip
    JM_VER="${var.jmeter_version}"
    cd /opt
    rm -rf apache-jmeter-* 2>/dev/null || true
    wget -q "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-$${JM_VER}.tgz"
    tar xzf "apache-jmeter-$${JM_VER}.tgz"
    rm -f "apache-jmeter-$${JM_VER}.tgz"
    ln -sfn "/opt/apache-jmeter-$${JM_VER}" /opt/apache-jmeter
    mkdir -p /home/ec2-user/jmeter-results
    chown ec2-user:ec2-user /home/ec2-user/jmeter-results
    cat >/etc/profile.d/jmeter.sh <<'ENV'
    export JMETER_HOME=/opt/apache-jmeter
    export PATH="$JMETER_HOME/bin:$PATH"
    ENV
    chmod +x /etc/profile.d/jmeter.sh
    echo "JMeter $${JM_VER} installed at /opt/apache-jmeter" >/var/log/jmeter-bootstrap.log
  EOT

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.jmeter_root_volume_size
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "${var.project_name}-jmeter-loadgen"
    Role = "jmeter"
  }
}
