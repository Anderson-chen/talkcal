# ── 模型檔的唯一來源 ─────────────────────────────────────────────────────────
# 本機的 C:\llm\models 在雲端換成這個 bucket。GGUF 不進 git（幾 GB），也不包進映像檔
# （換模型就要重 build、重推幾 GB），所以獨立放。
# 上傳一次：aws s3 cp C:/llm/models/Qwen3-8B-Q4_K_M.gguf s3://<bucket>/ --profile DEV
# 機器上的 deploy.sh 每次部署 sync 下來，model-seed 再照本機的流程驗 SHA256、複製進 volume。
# 只有 model_backend = gpu 才建：走 Bedrock 時模型在 AWS 那邊，機器上沒有模型檔。
resource "aws_s3_bucket" "models" {
  count  = local.gpu ? 1 : 0
  bucket = "${var.project}-models-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "models" {
  count  = local.gpu ? 1 : 0
  bucket = aws_s3_bucket.models[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "models" {
  count  = local.gpu ? 1 : 0
  bucket = aws_s3_bucket.models[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
