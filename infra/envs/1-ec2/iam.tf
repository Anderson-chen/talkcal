# ── EC2 的角色 ───────────────────────────────────────────────────────────────
# 四件事：讓 SSM Session Manager 連得進來（不必 SSH 金鑰）、讀 .env、寫和讀資料庫備份、（gpu 時）讀模型檔。
# 全部走 instance profile 拿臨時憑證，機器的角色沒有任何長期金鑰。
# 走 Bedrock 時，呼叫模型的權限不在這裡，在 bedrock.tf 的專用 user 上（理由見那裡）。
resource "aws_iam_role" "ec2" {
  name = "${var.project}-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 自己專案路徑底下的 SSM 參數（只讀）、備份 bucket（寫、讀，不能刪），gpu 時再加模型 bucket（只讀），別的都碰不到
resource "aws_iam_role_policy" "deploy" {
  name = "${var.project}-deploy"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [{
        Sid      = "ReadDotenv"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = aws_ssm_parameter.dotenv.arn
        # SecureString 用的是 AWS 代管金鑰 aws/ssm，它的金鑰政策已經允許同帳號經由 SSM 解密，
        # 不必另外給 kms:Decrypt
        },
        # 備份：能放、能拿（還原時下載），但沒有 DeleteObject。
        # 機器被入侵也刪不掉備份；保留期到了由 backups.tf 的 lifecycle 刪
        {
          Sid      = "WriteReadBackups"
          Effect   = "Allow"
          Action   = ["s3:PutObject", "s3:GetObject"]
          Resource = "${aws_s3_bucket.backups.arn}/postgres/*"
        },
        {
          Sid      = "ListBackups"
          Effect   = "Allow"
          Action   = ["s3:ListBucket"]
          Resource = aws_s3_bucket.backups.arn
      }],
      local.gpu ? [
        {
          Sid      = "ReadModels"
          Effect   = "Allow"
          Action   = ["s3:GetObject"]
          Resource = "${aws_s3_bucket.models[0].arn}/*"
        },
        {
          Sid      = "ListModels"
          Effect   = "Allow"
          Action   = ["s3:ListBucket"]
          Resource = aws_s3_bucket.models[0].arn
        },
      ] : [],
    )
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project}-ec2"
  role = aws_iam_role.ec2.name
}
