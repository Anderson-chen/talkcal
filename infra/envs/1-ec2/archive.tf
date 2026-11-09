# ── 舊資料的歸檔（#54）─────────────────────────────────────────────────────────
# app 每天凌晨把舊行程、舊的 AI 助理對話壓成 JSON Lines + gzip 放進這個 bucket，存好了才從 PostgreSQL 刪。
# 本機對應的是 deploy/compose.yaml 的 RustFS；app 用同一套 S3 API，只差 endpoint（compose.ec2.yaml 把它蓋成空白 = S3 本身）。
#
# 不跟 models bucket 共用：兩邊的存取權限、保留方式完全不同（模型檔誰都能重新上傳，歸檔是使用者的資料），
# 而且 models 只有 gpu 才建，歸檔兩種 model_backend 都要。
resource "aws_s3_bucket" "archive" {
  bucket = "${var.project}-archive-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "archive" {
  bucket = aws_s3_bucket.archive.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 刻意沒有的設定：
# - 版本控制（versioning）不開：刪帳號（#52）時歸檔要真的刪掉。開了版本控制，刪除只是加一個刪除標記，舊版本還在 ——
#   歸檔就變成刪不掉的備份。要防誤刪，靠的是「存好才刪資料庫」這個順序，不是 bucket 留舊版本。
# - 不轉 Glacier、Standard-IA：一包是「一個人一個月」的資料，通常只有幾 KB。IA 和 Glacier Instant Retrieval
#   每個物件最少按 128KB 計費、還有 30／90 天的最低存放期，對這種小物件反而比 Standard 貴；
#   Glacier Flexible／Deep Archive 取回要幾分鐘到幾小時，還原 API 等不了。量大到值得轉的時候，要先把小包合併成大包。
# - 不設過期刪除：歸檔留到使用者刪帳號為止（那時由 app 刪掉那個人的前綴），不替使用者決定「幾年後就丟掉」。
resource "aws_s3_bucket_lifecycle_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}

    # app 用單次 PutObject 上傳，不會留下分段上傳的碎片；這條是保險（例如有人手動用 aws cli 傳大檔傳到一半）
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ── app 存取歸檔用的身分 ──────────────────────────────────────────────────────
# app 跑在容器裡，而機器的 IMDS hop limit 刻意設成 1（ec2.tf）：容器拿不到機器角色的憑證。
# 所以跟 Bedrock 一樣，開一個專用 user，只准讀寫這個 bucket；key 經 SSM 的 .env 交給 app（secrets.tf）。
# key 外流最多就是這個 bucket 被讀寫，碰不到帳號的其他東西。
#
# 另一條路是把 hop limit 改成 2、讓容器用機器角色（臨時憑證，不必換 key）：代價是容器裡任何一個 SSRF 都拿得到機器的權限，
# 包括讀 SSM 裡的整份 .env。這個專案先選「key 的權限很窄」而不是「沒有長期 key」，跟 bedrock.tf 的取捨一致。
resource "aws_iam_user" "archive" {
  name = "${var.project}-archive"
}

resource "aws_iam_user_policy" "archive" {
  name = "${var.project}-archive-rw"
  user = aws_iam_user.archive.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # 放一包、讀一包、刪一包（刪帳號 #52）。沒有 PutBucket*、DeleteBucket：bucket 的設定只歸 Terraform 管
        Sid      = "ReadWriteObjects"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.archive.arn}/*"
      },
      {
        # 還原要列出某個人某個月底下有哪幾包
        Sid      = "ListObjects"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.archive.arn
      },
    ]
  })
}

# key 的明碼會在 Terraform state 裡：跟 DB 密碼一樣，靠 bootstrap 的 state bucket 加密、不公開。
# 換 key：terraform apply -replace=aws_iam_access_key.archive，再到機器上重跑 deploy.sh
resource "aws_iam_access_key" "archive" {
  user = aws_iam_user.archive.name
}
