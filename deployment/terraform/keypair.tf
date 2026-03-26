# Blank AWS account: create Key Pair from your local ~/.ssh/id_ed25519.pub (or id_rsa.pub)
resource "aws_key_pair" "deployer" {
  count      = var.public_key_path != null ? 1 : 0
  key_name   = "${var.project_name}-ssh"
  public_key = trimspace(file(var.public_key_path))
}

locals {
  ec2_key_name = var.public_key_path != null ? aws_key_pair.deployer[0].key_name : var.key_name
}
