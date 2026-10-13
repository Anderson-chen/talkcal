variable "region" {
  description = <<-DESC
    部署區域。預設東京：確定有 g6（L4）機型。台北 ap-east-2 延遲更低、價格也比較便宜，
    但還沒確認那裡有沒有 GPU 機型，確認之後再換（bootstrap 的 region 要一起換）。
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

variable "instance_type" {
  description = <<-DESC
    EC2 機型，要有 NVIDIA GPU。
    預設 g6.xlarge（L4 24GB、4 vCPU、16GB RAM）：llama-server 的映像檔編的是 CUDA_ARCH=89（Ada），
    L4 剛好是 sm_89，跟本機同一份映像檔直接能跑。
    g4dn（T4）比較便宜，但 T4 是 sm_75，要改 deploy/llama-server/Dockerfile 的 CUDA_ARCH 重新編。
    記憶體不夠時改 g6.2xlarge（32GB RAM），換機型是 stop → modify → start，EBS 上的資料不動。
  DESC
  type        = string
  default     = "g6.xlarge"
}

variable "root_volume_size" {
  description = <<-DESC
    根磁碟 GB。Deep Learning AMI 本身就佔幾十 GB；再加上 CUDA devel 的 build 階段（約 10GB）、
    模型檔兩份（S3 拉下來一份、model-seed 複製進 volume 一份，各約 5GB）、Postgres 和觀測資料。
  DESC
  type        = number
  default     = 150
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
