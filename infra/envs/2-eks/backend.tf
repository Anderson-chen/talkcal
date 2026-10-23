terraform {
  # 1.11：write-only 參數（DB 密碼不進 state，見 rds.tf）
  required_version = ">= 1.11"

  # 跟 1-ec2 同一個 state bucket，key 分開：拆掉一個階段不會動到另一個。
  # bucket / region / profile 放在 gitignore 掉的 backend.hcl（bucket 名稱帶帳號 ID）：
  #   cp backend.hcl.example backend.hcl
  #   terraform init -backend-config=backend.hcl
  backend "s3" {
    key          = "2-eks/terraform.tfstate"
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    # 3.x：provider 區塊的 kubernetes 改成屬性（kubernetes = { ... }），set 改成 list
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.38"
    }
    # ephemeral "random_password"（3.7 起）：產生的值只活在這次執行，不寫進 state
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}
