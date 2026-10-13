# ── Bootstrap：兩個階段共用、只跑一次 ─────────────────────────────────────────
# 雞生蛋問題：各階段的 Terraform state 要放 S3，但那個 bucket 本身也得有人建。
# 這個小 stack 用本機 state 建出它，之後各階段（envs/1-ec2、envs/2-eks）都把 state 放進去。
# 這裡只放兩樣東西：state bucket、花費告警。其他資源一律放各階段的資料夾。
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project   = "talkcal"
      ManagedBy = "terraform-bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}

# ── Terraform remote state bucket ─────────────────────────────────────────────
# 名稱帶帳號 ID：S3 bucket 名稱全球唯一，加上帳號 ID 不會撞到別人的。
resource "aws_s3_bucket" "tfstate" {
  bucket = "talkcal-tfstate-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true # state 掉了就失去所有資源的管理權，禁止誤刪
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled" # state 寫壞了可以拿回舊版
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # SSE-S3：免費。state 裡有 DB 密碼，一定要加密
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── 花費保險絲：實際花費到 80%、預測會超標時各寄一封信 ───────────────────────────
# GPU 機器按小時計費，忘了關機一個月就是幾百美元。告警不會幫你關機，只會叫你。
resource "aws_budgets_budget" "monthly" {
  name         = "talkcal-monthly"
  budget_type  = "COST"
  limit_amount = var.budget_limit_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
