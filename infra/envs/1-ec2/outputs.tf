output "instance_id" {
  description = "SSM 連線用：aws ssm start-session --target <id>"
  value       = aws_instance.app.id
}

output "public_ip" {
  value = aws_eip.main.public_ip
}

output "app_url" {
  description = "前端入口（Caddy 第一次啟動要花幾十秒跟 Let's Encrypt 拿憑證）"
  value       = "https://${local.site_address}"
}

output "model_backend" {
  value = var.model_backend
}

output "models_bucket" {
  description = "（gpu）GGUF 模型檔上傳到這裡。bedrock 時沒有"
  value       = local.gpu ? aws_s3_bucket.models[0].bucket : null
}

output "backups_bucket" {
  description = "資料庫備份（pg_dump）放在這裡的 postgres/ 底下。還原步驟：docs/runbooks/backup-restore.md"
  value       = aws_s3_bucket.backups.bucket
}

output "availability_zone" {
  description = "機器開在哪個可用區（自動挑有這個機型的）"
  value       = local.availability_zone
}

output "grafana_tunnel" {
  description = "把機器上的 Grafana 接回本機 http://localhost:3000"
  value       = "aws ssm start-session --target ${aws_instance.app.id} --region ${var.region} --profile ${var.aws_profile} --document-name AWS-StartPortForwardingSession --parameters portNumber=3000,localPortNumber=3000"
}

output "oauth_callback_urls" {
  description = "要在 Google Cloud Console（Authorized redirect URIs）、GitHub OAuth App（callback URL）登記的回呼網址。Elastic IP 換了網址就跟著換，要重新登記"
  value = {
    google = "https://${local.site_address}/api/auth/oauth2/callback/google"
    github = "https://${local.site_address}/api/auth/oauth2/callback/github"
  }
}

output "heartbeat_url" {
  description = "Grafana 打的 heartbeat 網址（#40）。power.sh stop 從這裡取 check 的 UUID 去暫停"
  value       = var.heartbeat_url
  sensitive   = true
}
