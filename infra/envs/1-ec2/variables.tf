variable "region" {
  description = <<-DESC
    部署區域。東京：2026-10-13 查過，台北 ap-east-2 沒有任何 G 系列 GPU 機型，
    東京的 g6.xlarge 在 1a、1c 有。改區域的話 bootstrap 的 region 要一起換。
  DESC
  type        = string
  default     = "ap-northeast-1"
}

variable "aws_profile" {
  description = "本機 AWS CLI profile 名稱"
  type        = string
  default     = "DEV"
}

variable "project" {
  description = "專案名稱，用在資源命名、標籤、SSM 參數路徑"
  type        = string
  default     = "talkcal"
}

variable "model_backend" {
  description = <<-DESC
    模型在哪裡跑：
      bedrock：一般機型，app 呼叫 Bedrock 的 Qwen3（OpenAI 相容 API）。不需要 GPU 配額。
      gpu    ：GPU 機型，跟本機一樣自己跑 llama-server，模型從 S3 拉。
    預設 bedrock：2026-10-13 申請東京 G 系列配額被拒（新帳號預設 0）。配額下來後改成 gpu 就能切回去，
    其他設定（VPC、Caddy、DB、觀測）兩邊共用。理由見 README「為什麼先走 Bedrock」。
  DESC
  type        = string
  default     = "bedrock"

  validation {
    condition     = contains(["bedrock", "gpu"], var.model_backend)
    error_message = "model_backend 只能是 bedrock 或 gpu。"
  }
}

variable "instance_type" {
  description = <<-DESC
    EC2 機型。不給就依 model_backend 選：
      bedrock：m7i-flex.large（2 vCPU、8GB）。app、Postgres、Caddy、整組觀測放得下；符合免費方案資格。
      gpu    ：g6.xlarge（L4 24GB、4 vCPU、16GB）。llama-server 映像檔編的是 CUDA_ARCH=89（Ada），
               L4 剛好是 sm_89；g4dn（T4）是 sm_75，要改 deploy/llama-server/Dockerfile 重新編。
    換機型是 stop → modify → start，EBS 上的資料不動。
  DESC
  type        = string
  default     = null
}

variable "root_volume_size" {
  description = <<-DESC
    根磁碟 GB。不給就依 model_backend 選：
      bedrock：30。Ubuntu 本身幾 GB，加上 build 映像檔、Postgres 和觀測資料。
      gpu    ：150。Deep Learning AMI 本身就佔幾十 GB（不能小於 AMI 的 75GB），再加上 CUDA devel 的
               build 階段（約 10GB）、模型檔兩份（S3 拉下來一份、model-seed 複製進 volume 一份，各約 5GB）。
  DESC
  type        = number
  default     = null
}

variable "bedrock_model" {
  description = <<-DESC
    model_backend = bedrock 時用的模型。Qwen3 32B：跟本機的 Qwen3-8B 同一家族，
    2026-10-13 在東京實測過 bedrock-mantle 端點的 json_schema 輸出和工具呼叫都能用。
  DESC
  type        = string
  default     = "qwen.qwen3-32b"
}

variable "bedrock_key_days" {
  description = <<-DESC
    Bedrock API key 幾天後過期。到期前重跑 apply 換新的：
      terraform apply -replace=aws_iam_service_specific_credential.bedrock[0]
    設上限是為了 key 萬一外流，最多也只能用到這天。
  DESC
  type        = number
  default     = 90
}

variable "backup_retention_days" {
  description = <<-DESC
    資料庫備份在 S3 留幾天（#45）。30：誤刪、資料被程式寫壞，通常幾天內就會發現；
    留一個月是給「過了一陣子才有人說我的行程不見了」的情況。dump 只有幾百 KB，一個月的每小時備份也不到 1 GB。
  DESC
  type        = number
  default     = 30
}

variable "repo_url" {
  description = "機器開機時 git clone 的來源。repo 是公開的，不需要憑證"
  type        = string
  default     = "https://github.com/Anderson-chen/talkcal.git"
}

variable "git_ref" {
  description = "要部署的分支或 tag。之後做了版本 tag（#25）就改成部署 tag"
  type        = string
  default     = "master"
}

variable "allow_ssh" {
  description = <<-DESC
    要不要開 22 埠給目前這台電腦的 IP。預設 false：登入機器走 SSM Session Manager
    （不必管金鑰、有稽核紀錄、家裡 IP 換了也不用改規則）。
  DESC
  type        = bool
  default     = false
}

variable "ssh_cidr" {
  description = "allow_ssh = true 時，允許連 22 埠的來源，例如 \"203.0.113.5/32\""
  type        = string
  default     = null
}

# ── Google、GitHub 登入（#127）────────────────────────────────────────────
# 值不寫在這裡也不進 git：複製 oauth.auto.tfvars.example 成 oauth.auto.tfvars（*.tfvars 已 gitignore）再填，
# 或用環境變數 TF_VAR_google_client_secret 這類名字給。apply 時寫進 SSM 的 .env，部署時交給 app。
# 沒給就是空字串 → 那個登入方式不開，雲端只有帳密（跟本機沒設時一樣）。
# sensitive：plan、apply 的輸出不印值；但 state 裡還是明碼（跟 DB 密碼一樣），所以 state bucket 要加密、不公開
variable "google_client_id" {
  description = "Google OAuth client ID（雲端用的回呼網址要先登記，見 terraform output oauth_callback_urls）"
  type        = string
  default     = ""
}

variable "google_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "github_client_id" {
  description = "GitHub OAuth App 的 client ID。GitHub 一個 App 只能填一個回呼網址，雲端要另外建一個，不能跟本機共用"
  type        = string
  default     = ""
}

variable "github_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}
