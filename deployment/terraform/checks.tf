check "ssh_key_configured" {
  assert {
    condition = var.public_key_path != null || var.key_name != null
    error_message = "Set public_key_path (recommended for new AWS) OR key_name (existing key pair in this region)."
  }
}
