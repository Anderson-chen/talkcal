provider "aws" {
  region  = var.region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project     = var.project
      Environment = "2-eks"
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront 用的 WAF 一定要建在 us-east-1（CloudFront 是全球服務，它的 WAF、ACM 憑證都放那裡）
provider "aws" {
  alias   = "us_east_1"
  region  = "us-east-1"
  profile = var.aws_profile

  default_tags {
    tags = {
      Project     = var.project
      Environment = "2-eks"
      ManagedBy   = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

# ── 叢集裡的東西（ALB controller、External Secrets、namespace、RBAC）────────────
# 跟建叢集放在同一個根目錄：一次 apply 就從零到能部署。代價是 provider 要等叢集建好才連得上，
# 第一次 apply 時 Terraform 會先建叢集、再裝 helm chart（它們都依賴 aws_eks_cluster 的輸出）。
# 憑證用 exec 每次現拿（aws eks get-token，15 分鐘有效），不把 token 寫進 state 或 kubeconfig。
locals {
  kube_exec = {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", aws_eks_cluster.main.name, "--region", var.region, "--profile", var.aws_profile]
  }
}

provider "kubernetes" {
  host                   = aws_eks_cluster.main.endpoint
  cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)

  exec {
    api_version = local.kube_exec.api_version
    command     = local.kube_exec.command
    args        = local.kube_exec.args
  }
}

provider "helm" {
  kubernetes = {
    host                   = aws_eks_cluster.main.endpoint
    cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)
    exec                   = local.kube_exec
  }
}
