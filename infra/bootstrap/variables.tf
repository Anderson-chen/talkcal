variable "region" {
  description = "state bucket 放哪個區域。跟各階段的部署區域一致，延遲最低、也不會有跨區傳輸費"
  type        = string
  default     = "ap-northeast-1" # 東京。理由見 envs/1-ec2/variables.tf 的 region
}

variable "aws_profile" {
  description = "本機 AWS CLI profile 名稱"
  type        = string
  default     = "DEV"
}

variable "budget_limit_usd" {
  description = <<-DESC
    每月花費告警門檻（USD）。GPU 機器平常關機、只在展示時開，一個月開幾十小時的量級；
    忘了關機的話幾天就會超過，告警就是為了抓這個。
  DESC
  type        = string
  default     = "50"
}

# 刻意不給預設值：repo 是公開的，信箱不寫進版控。
# 放在 terraform.tfvars（已 gitignore），或 apply 時用 -var="alert_email=..." 給。
variable "alert_email" {
  description = "花費告警收件信箱"
  type        = string
}
