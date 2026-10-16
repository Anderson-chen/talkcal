# ── S3：前端靜態檔、觀測的區塊、（gpu）模型檔 ──────────────────────────────────
# 全部：加密、擋公開存取、名稱帶帳號 ID（bucket 名稱全球唯一）。平常 destroy，force_destroy 讓有檔案的也刪得掉。
locals {
  buckets = merge(
    {
      web = "前端靜態檔（web/ 的 npm run build），只給 CloudFront 讀"
      # ADR 0003：三個資料庫各一個，跟本機的三個 volume 對應
      mimir = "Mimir 的指標區塊"
      loki  = "Loki 的 log 區塊"
      tempo = "Tempo 的 trace 區塊"
    },
    local.gpu ? { models = "GGUF 模型檔（本機 C:/llm/models 的雲端版），llm-chat 的 initContainer 拉" } : {},
  )
}

resource "aws_s3_bucket" "this" {
  for_each      = local.buckets
  bucket        = "${local.name}-eks-${each.key}-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
  tags          = { Purpose = each.value }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 觀測資料保留 30 天：本機是 volume 滿了才清，雲端按 GB 收錢，給個上限
resource "aws_s3_bucket_lifecycle_configuration" "obs" {
  for_each = { for k in ["mimir", "loki", "tempo"] : k => aws_s3_bucket.this[k] }
  bucket   = each.value.id

  rule {
    id     = "expire-30d"
    status = "Enabled"
    filter {}
    expiration {
      days = 30
    }
  }
}

# 其他檔案用名字取比較好讀
locals {
  web_bucket = aws_s3_bucket.this["web"]
}
