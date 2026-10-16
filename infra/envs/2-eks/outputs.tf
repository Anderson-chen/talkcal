output "app_url" {
  description = "使用者開的網址（CloudFront 自帶 HTTPS）"
  value       = "https://${aws_cloudfront_distribution.main.domain_name}"
}

output "kubeconfig_command" {
  description = "本機 kubectl 連上叢集"
  value       = "aws eks update-kubeconfig --name ${aws_eks_cluster.main.name} --region ${var.region} --profile ${var.aws_profile}"
}

output "model_backend" {
  value = var.model_backend
}

output "github_deploy_role_arn" {
  description = "填進 GitHub environment eks 的變數 AWS_ROLE_ARN（不是祕密，但帶帳號 ID，不寫進 repo）"
  value       = aws_iam_role.github_deploy.arn
}

output "github_environment_variables" {
  description = "GitHub repo → Settings → Environments → eks → Variables 要填的值（.github/workflows/eks-deploy.yml 讀）"
  value = {
    AWS_ROLE_ARN       = aws_iam_role.github_deploy.arn
    AWS_REGION         = var.region
    EKS_CLUSTER        = aws_eks_cluster.main.name
    ECR_REGISTRY       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
    WEB_BUCKET         = local.web_bucket.bucket
    CLOUDFRONT_DIST_ID = aws_cloudfront_distribution.main.id
    TARGET_GROUP_NAME  = aws_lb_target_group.app.name
  }
}

output "ecr_repositories" {
  value = { for k, r in aws_ecr_repository.main : k => r.repository_url }
}

output "models_bucket" {
  description = "（gpu）GGUF 模型檔和 SHA256SUMS 上傳到這裡。bedrock 時沒有"
  value       = local.gpu ? aws_s3_bucket.this["models"].bucket : null
}

output "observability_buckets" {
  description = "Mimir、Loki、Tempo 的區塊放這裡（ADR 0003）"
  value       = { for k in ["mimir", "loki", "tempo"] : k => aws_s3_bucket.this[k].bucket }
}

output "rds_endpoint" {
  value = aws_db_instance.main.endpoint
}

output "oauth_callback_urls" {
  description = "要在 Google、GitHub 登記的回呼網址。CloudFront 的網址重建 distribution 才會變"
  value = {
    google = "https://${aws_cloudfront_distribution.main.domain_name}/api/auth/oauth2/callback/google"
    github = "https://${aws_cloudfront_distribution.main.domain_name}/api/auth/oauth2/callback/github"
  }
}
