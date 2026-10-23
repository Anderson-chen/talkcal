# ── Bedrock：model_backend = bedrock 時，app 呼叫模型用的 API key（跟 1-ec2 同一份設計）─────────
# app 用 Spring AI 的 OpenAI client 打 bedrock-mantle 的 Chat Completions（OpenAI 相容），
# 程式不用改，只換 base-url、key、模型名稱（見 secrets.tf 的 talkcal/app 祕密和 overlays/eks 的 app 環境變數）。
# OpenAI client 只會送 Bearer key，不會做 AWS 的 SigV4 簽章，所以要一把 Bedrock API key。
#
# 長期的 Bedrock API key 在 AWS 裡是「綁在 IAM user 上的服務專用憑證」：key 的權限 = 這個 user 的權限，
# 而且只能用在 Bedrock。所以開一個專用 user，只給呼叫模型的權限：key 外流，最多就是被拿去叫模型、花 token 錢，
# 碰不到這個帳號的其他東西。不用 Pod Identity：OpenAI client 只會送 Bearer key，用不到臨時憑證（ADR 0002）。
#
# 不用這把 key 的做法是 Spring AI 的 Bedrock Converse 模組（直接吃 Pod Identity）：程式要多一套供應商設定，
# 先用 key 驗證模型合不合用，之後再換（#72 的決定）。

resource "aws_iam_user" "bedrock" {
  count = local.gpu ? 0 : 1
  name  = "${var.project}-eks-bedrock"
  # 沒有密碼、沒有 access key：這個 user 唯一的憑證就是下面那把 Bedrock API key
}

resource "aws_iam_user_policy" "bedrock" {
  count = local.gpu ? 0 : 1
  name  = "${var.project}-eks-bedrock-invoke"
  user  = aws_iam_user.bedrock[0].name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # 准許用長期 API key 呼叫 mantle 端點。只准 LONG_TERM：這個 user 也不該用別種 token
        Sid      = "UseLongTermKeyOnMantle"
        Effect   = "Allow"
        Action   = "bedrock-mantle:CallWithBearerToken"
        Resource = "*"
        Condition = {
          StringEquals = { "bedrock-mantle:bearerTokenType" = "LONG_TERM" }
        }
      },
      {
        # 呼叫模型。只准部署的這個區域：key 外流也不能拿去別的區域用
        Sid      = "InvokeInRegion"
        Effect   = "Allow"
        Action   = "bedrock-mantle:CreateInference"
        Resource = "*"
        Condition = {
          StringEquals = { "aws:RequestedRegion" = var.region }
        }
      },
    ]
  })
}

# Terraform 產生 key，連同其他設定寫進 Secrets Manager 的 talkcal/app（secrets.tf），External Secrets 交給 Pod。
# key 的明碼會在 Terraform state 裡（DB 密碼在這個階段不會，見 rds.tf），靠 bootstrap 的 state bucket 加密、不公開。
# 到期前換新：terraform apply -replace='aws_iam_service_specific_credential.bedrock[0]'，External Secrets 一小時內同步，再 kubectl -n talkcal rollout restart deployment/app
resource "aws_iam_service_specific_credential" "bedrock" {
  count               = local.gpu ? 0 : 1
  service_name        = "bedrock.amazonaws.com"
  user_name           = aws_iam_user.bedrock[0].name
  credential_age_days = var.bedrock_key_days
}
