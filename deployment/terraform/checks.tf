check "ssh_key_configured" {
  assert {
    condition     = var.public_key_path != null || var.key_name != null
    error_message = "Set public_key_path (recommended for new AWS) OR key_name (existing key pair in this region)."
  }
}

check "ssh_ingress_not_world_open_by_default" {
  assert {
    condition     = var.allowed_ssh_cidr != "0.0.0.0/0" || var.permit_unsafe_wide_ssh
    error_message = "allowed_ssh_cidr must not be 0.0.0.0/0 unless permit_unsafe_wide_ssh=true. Use your public IP/32 (see terraform.tfvars.example)."
  }
}
