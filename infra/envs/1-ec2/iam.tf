# ── EC2 的角色 ───────────────────────────────────────────────────────────────
# 三件事：讓 SSM Session Manager 連得進來（不必 SSH 金鑰）、讀模型檔、讀 .env。
# 全部走 instance profile 拿臨時憑證，機器上不放任何長期金鑰。
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

# 只讀：模型 bucket 和自己專案路徑底下的 SSM 參數，別的都碰不到
resource "aws_iam_role_policy" "deploy" {
  name = "${var.project}-deploy"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadModels"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.models.arn}/*"
      },
      {
        Sid      = "ListModels"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.models.arn
      },
      {
        Sid      = "ReadDotenv"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = aws_ssm_parameter.dotenv.arn
        # SecureString 用的是 AWS 代管金鑰 aws/ssm，它的金鑰政策已經允許同帳號經由 SSM 解密，
        # 不必另外給 kms:Decrypt
      },
    ]
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project}-ec2"
  role = aws_iam_role.ec2.name
}
