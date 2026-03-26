locals {
  # Amazon Linux 2023: Java 17 + git for manual deploy (git pull / scp JARs)
  user_data_base = <<-EOT
    #!/bin/bash
    set -e
    dnf update -y
    dnf install -y java-17-amazon-corretto-headless git
  EOT
}
