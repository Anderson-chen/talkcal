variable "region" {
  description = "部署區域。跟 1-ec2 一樣用東京（台北 ap-east-2 沒有 G 系列 GPU 機型，見 1-ec2 的 variables.tf）"
  type        = string
  default     = "ap-northeast-1"
}

variable "aws_profile" {
  description = "本機 AWS CLI profile 名稱"
  type        = string
  default     = "DEV"
}

variable "project" {
  description = "專案名稱，用在資源命名、標籤"
  type        = string
  default     = "talkcal"
}

variable "kubernetes_version" {
  description = <<-DESC
    EKS 的 Kubernetes 版本。2026-10 標準支援的最新是 1.37。
    標準支援大約 14 個月，過了自動進 extended support，控制平面從 0.10 變 0.60 USD/h：每年要升。
  DESC
  type        = string
  default     = "1.37"
}

# ── 模型：沿用 1-ec2 的開關（ADR 0002）────────────────────────────────────────
variable "model_backend" {
  description = <<-DESC
    模型在哪裡跑：
      bedrock：app 呼叫 Bedrock 的 Qwen3（OpenAI 相容 API），不建 GPU 節點。不需要 GPU 配額。
      gpu    ：多一個 g6.xlarge 的節點群組跑 llama-server，模型從 S3 拉（Pod 的寫法在 deploy/k8s/components/llm-chat-gpu，#56）。
    預設 bedrock：2026-10-13 東京 G 系列配額申請被拒（#72）。
  DESC
  type        = string
  default     = "bedrock"

  validation {
    condition     = contains(["bedrock", "gpu"], var.model_backend)
    error_message = "model_backend 只能是 bedrock 或 gpu。"
  }
}

variable "bedrock_model" {
  description = "model_backend = bedrock 時用的模型，跟 1-ec2 同一個（2026-10-13 驗證過 json_schema 和工具呼叫）"
  type        = string
  default     = "qwen.qwen3-32b"
}

variable "bedrock_key_days" {
  description = "Bedrock API key 幾天後過期；到期前 terraform apply -replace='aws_iam_service_specific_credential.bedrock[0]'"
  type        = number
  default     = 90
}

variable "gpu_instance_type" {
  description = "GPU 節點機型。g6.xlarge（L4，sm_89）：llama-server 映像檔編的是 CUDA_ARCH=89，不用重編"
  type        = string
  default     = "g6.xlarge"
}

variable "gpu_node_count" {
  description = "model_backend = gpu 時 GPU 節點開幾台。沒人用時設 0 再 apply（節點群組留著，min 是 0）"
  type        = number
  default     = 1
}

# ── 一般節點 ─────────────────────────────────────────────────────────────────
variable "node_instance_type" {
  description = <<-DESC
    一般節點的機型。t3.large（2 vCPU、8 GB、最多 35 個 Pod）：
    app ×2（各要 768Mi）、ALB controller、External Secrets、CoreDNS，再加觀測那組（Mimir、Loki、Tempo、Grafana、Alloy 約 3–4 GB，ADR 0003）。
    t3.medium（4 GB、17 個 Pod）不放觀測的話夠，放了就不夠。
  DESC
  type        = string
  default     = "t3.large"
}

variable "node_count" {
  description = "一般節點台數。至少 2：兩個可用區各一台，一台掛了 app 還有一份在另一台（這是上 EKS 的目的）"
  type        = number
  default     = 2

  validation {
    condition     = var.node_count >= 2
    error_message = "至少 2 台：只有 1 台的話，換節點或節點掛掉時服務就停，跟 1-ec2 沒差。"
  }
}

variable "node_capacity_type" {
  description = <<-DESC
    ON_DEMAND 或 SPOT。SPOT 便宜約六到七成，但 AWS 可能提前兩分鐘通知收回。
    app 有兩份、PodDisruptionBudget 之類的保護還沒做，所以預設 ON_DEMAND；練習 rollout 時可以改 SPOT 省錢。
  DESC
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type 只能是 ON_DEMAND 或 SPOT。"
  }
}

# ── 網路 ─────────────────────────────────────────────────────────────────────
variable "vpc_cidr" {
  description = "VPC 位址。跟 1-ec2 的 10.0.0.0/16 錯開，兩個階段並存時才能 peering 或對照"
  type        = string
  default     = "10.1.0.0/16"
}

variable "nat_per_az" {
  description = <<-DESC
    每個可用區各一個 NAT Gateway（true），或兩區共用一個（false）。
    共用的話那一區掛了，另一區的節點出不了網（拉不到映像檔、叫不到 Bedrock）；但每個 NAT 每月約 45 USD，作品集先省。
  DESC
  type        = bool
  default     = false
}

variable "cluster_public_access_cidrs" {
  description = <<-DESC
    誰連得到 EKS 的公開 API 端點。預設全開，靠 IAM 認證擋：GitHub 代管的 runner IP 不固定，收窄的話 workflow 連不進來。
    只從家裡操作、或改用 VPC 裡的 self-hosted runner 時，改成 ["<你的 IP>/32"]（Trivy AWS-0040、0041）
  DESC
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ── 資料庫 ───────────────────────────────────────────────────────────────────
variable "db_instance_class" {
  description = "RDS 機型。db.t4g.micro（2 vCPU、1 GB）：作品集的流量，東京約 0.025 USD/h"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "PostgreSQL 大版本，跟 deploy/compose.yaml 的 postgres:18 一致。只寫大版本，小版本 RDS 自己挑最新、自動升"
  type        = string
  default     = "18"
}

variable "db_multi_az" {
  description = "RDS 要不要多可用區（備援、價格兩倍）。作品集先關；資料可靠性見 #45 備份與還原"
  type        = bool
  default     = false
}

variable "db_password_version" {
  description = <<-DESC
    DB 密碼的版本號。密碼用 write-only 參數送給 RDS，不進 state，Terraform 也就不知道它變了沒——
    要換密碼就把這個數字加一再 apply（例如懷疑外洩）。External Secrets 一小時內會同步到 Pod，之後 rollout restart app。
  DESC
  type        = number
  default     = 1
}

# ── 前端、限流 ───────────────────────────────────────────────────────────────
variable "api_origin_read_timeout" {
  description = <<-DESC
    CloudFront 等 /api 回應最多幾秒。預設上限是 60，要更長要先到 Service Quotas 申請（最多 180）。
    app 叫模型最久等 2 分鐘，超過這裡的秒數會變成 504（ADR 0005 的代價）。
  DESC
  type        = number
  default     = 60
}

# ── GitHub Actions ───────────────────────────────────────────────────────────
variable "github_repo" {
  description = "可以用 OIDC 拿部署角色的 repo"
  type        = string
  default     = "Anderson-chen/talkcal"
}

variable "github_environment" {
  description = "只有跑在這個 GitHub environment 的 job 拿得到部署角色（.github/workflows/eks-deploy.yml）"
  type        = string
  default     = "eks"
}

variable "create_github_oidc_provider" {
  description = <<-DESC
    要不要建 GitHub 的 OIDC provider（token.actions.githubusercontent.com）。一個帳號只能有一個，
    別的專案已經建過的話設 false，改用既有的。
  DESC
  type        = bool
  default     = true
}

# ── Google、GitHub 登入（#127），跟 1-ec2 同樣的變數 ─────────────────────────────
# 值放不進 git 的 oauth.auto.tfvars（*.tfvars 已 gitignore），或 TF_VAR_google_client_secret 這類環境變數。
variable "google_client_id" {
  type    = string
  default = ""
}

variable "google_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "github_client_id" {
  type    = string
  default = ""
}

variable "github_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}
