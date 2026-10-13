terraform {
  required_version = ">= 1.10"

  # backend 區塊不能用變數，bucket 名稱又帶帳號 ID、不想寫進公開的 repo，
  # 所以這裡只寫各環境一樣的部分，bucket / region / profile 放在 backend.hcl（已 gitignore）：
  #   cp backend.hcl.example backend.hcl   # 填入 bootstrap 輸出的 state_bucket
  #   terraform init -backend-config=backend.hcl
  # key 帶階段名稱：之後 2-eks 用自己的 key，拆掉這個階段時它的 state 還留著可以追溯。
  backend "s3" {
    key          = "1-ec2/terraform.tfstate"
    use_lockfile = true # Terraform 1.10+ 的 S3 原生鎖，不必另建 DynamoDB
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}
