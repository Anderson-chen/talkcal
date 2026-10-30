# ── 資料庫備份放這裡（#45）──────────────────────────────────────────────────────
# 機器上的 deploy/backup/pg-backup.sh 做 pg_dump，infra/envs/1-ec2/backup-to-s3.sh 傳上來。
# 放在機器外面：EBS 壞了、機器被 terminate、`terraform destroy`，資料庫跟著沒，備份還在。
# 什麼時候備份、RPO／RTO 為什麼是那樣：docs/runbooks/backup-restore.md。
#
# force_destroy = false：bucket 裡有東西時 `terraform destroy` 會失敗、停下來，不會順手把最後一份備份也刪掉。
# 跟預設值一樣，明寫是怕之後有人為了 destroy 方便順手改掉。真的要收攤，先決定要不要把備份下載回來，再清空 bucket。
resource "aws_s3_bucket" "backups" {
  bucket        = "${var.project}-backups-${data.aws_caller_identity.current.account_id}"
  force_destroy = false
}

# 開版本控制：機器的角色沒有刪除權限（iam.tf），但能 PutObject。
# 萬一機器被入侵、拿同一個 key 寫進垃圾，舊的版本還在，不會被蓋掉
resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 保留多久：由 S3 自己刪，不靠機器（機器平常關機，靠它清的話關機期間永遠不會清）。
# 機器沒有刪除權限，所以也只能由這裡刪
resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "expire-postgres-dumps"
    status = "Enabled"

    filter {
      prefix = "postgres/"
    }

    expiration {
      days = var.backup_retention_days
    }

    # 被蓋掉、被刪掉的舊版本再留 7 天：發現「備份被寫壞」通常不會拖太久，留久了只是多付錢
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}
