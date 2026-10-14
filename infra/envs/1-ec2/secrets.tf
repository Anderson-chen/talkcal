# ── 密碼與部署設定 ───────────────────────────────────────────────────────────
# 本機的 compose 把 DB 密碼寫死成 talkcal（#14 的缺口）。雲端上改由 Terraform 隨機產生，
# 存進 SSM Parameter Store（SecureString，用 AWS 代管的金鑰，免費）。
# 機器部署時才拉下來寫成 deploy/.env，明碼不進 git、不進 user_data、不進映像檔。
# 代價：密碼會出現在 Terraform state 裡，所以 bootstrap 的 state bucket 一定要加密、不公開。
locals {
  # sslip.io 把 IP 編進網域（talkcal.1-2-3-4.sslip.io → 1.2.3.4）：免費、免註冊，
  # 而且是公開解析得到的真網域，Let's Encrypt 願意簽憑證——不必買網域也有 HTTPS。
  # 代價是 IP 一換網址就換，所以配了 Elastic IP。
  site_address = "${var.project}.${replace(aws_eip.main.public_ip, ".", "-")}.sslip.io"

  # 模型檔在機器上的位置（gpu）：deploy.sh 從 S3 sync 到這裡，compose.gpu.yaml 把它掛給 model-seed
  models_dir = "/opt/${var.project}/models"

  # .env 裡依 model_backend 不同的那幾行
  backend_env = local.gpu ? "MODELS_DIR=${local.models_dir}" : join("\n", [
    # mantle 的 OpenAI 相容端點。帶 /v1：OpenAI client 在後面直接接 /chat/completions
    "BEDROCK_BASE_URL=https://bedrock-mantle.${var.region}.api.aws/v1",
    "BEDROCK_MODEL=${var.bedrock_model}",
    "BEDROCK_API_KEY=${aws_iam_service_specific_credential.bedrock[0].service_credential_secret}",
  ])
}

resource "random_password" "db" {
  length  = 32
  special = false # 不用特殊字元：避開 JDBC URL、shell、YAML 的跳脫問題
}

# 一份完整的 .env，compose 直接讀。機器上不必再組字串。
resource "aws_ssm_parameter" "dotenv" {
  name        = "/${var.project}/dotenv"
  description = "deploy/.env 的內容（docker compose 讀）"
  type        = "SecureString"

  value = <<-ENV
    # 由 Terraform 產生，不要手改：下次 apply 會蓋掉。
    # compose.ec2.yaml、compose.${var.model_backend}.yaml 用到的變數都在這裡。
    SITE_ADDRESS=${local.site_address}
    DB_PASSWORD=${random_password.db.result}
    ${local.backend_env}
  ENV
}
