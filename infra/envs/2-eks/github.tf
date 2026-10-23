# ── GitHub Actions 用 OIDC 拿臨時憑證（ADR 0004）──────────────────────────────
# repo 裡不放任何長期的 AWS key：workflow 跟 GitHub 要一張 OIDC token，拿去換這個角色的臨時憑證（1 小時）。
# 只認 repo:<repo>:environment:eks —— 只有 .github/workflows/eks-deploy.yml 裡標了 environment: eks 的 job 拿得到；
# 別的 workflow、別人 fork 開的 PR 都換不到。environment 還可以在 GitHub 設「要人按核准才跑」。
resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_github_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint 不必給：AWS 對 GitHub 這個 provider 用自己信任的 CA 驗，2023 年起不看 thumbprint
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

resource "aws_iam_role" "github_deploy" {
  name                 = "${local.name}-github-deploy"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:environment:${var.github_environment}"
        }
      }
    }]
  })
}

# 部署要的四件事：推映像檔、傳前端、清 CloudFront 快取、連得上叢集（叢集裡能做什麼由 addons.tf 的 RBAC 決定）
resource "aws_iam_role_policy" "github_deploy" {
  name = "deploy"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*" # 這個動作不分 repo
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:DescribeImages",
          "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage",
        ]
        Resource = [for r in aws_ecr_repository.main : r.arn]
      },
      {
        Sid      = "WebUpload"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = local.web_bucket.arn
      },
      {
        Sid      = "WebObjects"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject"]
        Resource = "${local.web_bucket.arn}/*"
      },
      {
        Sid      = "Invalidate"
        Effect   = "Allow"
        Action   = "cloudfront:CreateInvalidation"
        Resource = aws_cloudfront_distribution.main.arn
      },
      {
        Sid      = "Cluster"
        Effect   = "Allow"
        Action   = "eks:DescribeCluster" # aws eks update-kubeconfig 要
        Resource = aws_eks_cluster.main.arn
      },
      {
        # workflow 用名字查 target group 的 ARN 寫進 TargetGroupBinding（overlays/eks/params.env）
        Sid      = "FindTargetGroup"
        Effect   = "Allow"
        Action   = "elasticloadbalancing:DescribeTargetGroups"
        Resource = "*" # Describe 類不支援資源層級
      },
    ]
  })
}

# 叢集的門票：把這個 IAM 角色對應到 k8s 群組 talkcal-deployers。不掛任何 access policy（那是 cluster 範圍的），
# 權限全由 addons.tf 的 RoleBinding 給，只在 talkcal namespace 裡
resource "aws_eks_access_entry" "github_deploy" {
  cluster_name      = aws_eks_cluster.main.name
  principal_arn     = aws_iam_role.github_deploy.arn
  kubernetes_groups = ["talkcal-deployers"]
  type              = "STANDARD"
}
