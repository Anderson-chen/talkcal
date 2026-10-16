# ── app 要的其他設定：一份 Secrets Manager 祕密 talkcal/app ─────────────────────
# External Secrets 把它拆成 k8s Secret（deploy/k8s/overlays/eks/external-secrets.yaml）：
#   talkcal-oauth    Google、GitHub 的 client id／secret（鍵名跟 #56 的 base 一樣，沒填就是空的、登入頁只有帳密）
#   talkcal-runtime  資料庫位址、模型的位址／key／名稱
# 跟 1-ec2 的 SSM .env 是同一份內容，換成 Secrets Manager：每月 0.40 USD／份，換來 External Secrets 原生支援、版本紀錄。
# Bedrock key 和 OAuth secret 仍會出現在 state 裡（跟 1-ec2 一樣靠 state bucket 加密）。
locals {
  model_env = local.gpu ? {
    # gpu：llm-chat 在同一個 namespace，用 Service 名稱找（跟 application-docker.properties 一樣，其實不蓋也行）
    ai_base_url = "http://llm-chat:8080/v1"
    ai_api_key  = "not-used-by-llama-server"
    ai_model    = "qwen3-8b"
    } : {
    # bedrock：mantle 的 OpenAI 相容端點，帶 /v1（OpenAI client 在後面接 /chat/completions）
    ai_base_url = "https://bedrock-mantle.${var.region}.api.aws/v1"
    ai_api_key  = aws_iam_service_specific_credential.bedrock[0].service_credential_secret
    ai_model    = var.bedrock_model
  }
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.name}/app"
  description             = "talkcal app 的設定（External Secrets 同步到 talkcal-oauth、talkcal-runtime）"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode(merge(local.model_env, {
    datasource_url       = "jdbc:postgresql://${aws_db_instance.main.endpoint}/talkcal"
    google_client_id     = var.google_client_id
    google_client_secret = var.google_client_secret
    github_client_id     = var.github_client_id
    github_client_secret = var.github_client_secret
  }))
}
