# ── AMI：AWS 的 Deep Learning Base AMI（Ubuntu 24.04、開源版 NVIDIA 驅動） ─────
# NVIDIA 驅動、Docker、NVIDIA Container Toolkit 都裝好了，不必自己在 user_data 裝驅動
# （自己裝要對核心版本、要重開機，最容易壞）。
# 用 SSM 公開參數抓最新版，不寫死 AMI ID：AMI 每幾週輪替，寫死的舊 ID 會下架。
# 注意：llama-server 映像檔是 CUDA 13，驅動要 580 以上；開機後 user_data 會印 nvidia-smi 確認。
data "aws_ssm_parameter" "dlami" {
  name = "/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id"
}

# ── 固定 IP ──────────────────────────────────────────────────────────────────
# 網域由 IP 推出來（sslip.io），IP 一換網址就換。Elastic IP 讓機器可以關機省錢而位址不變。
# 先建 EIP、再開機器：.env 裡的網域要在部署前就算好。
resource "aws_eip" "main" {
  domain = "vpc"

  tags = {
    Name = "${var.project}-eip"
  }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.dlami.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required" # 強制 IMDSv2：擋掉用 SSRF 偷機器憑證的典型路徑
    # hop limit 維持 1：容器多隔一層網路，連不到 IMDS，拿不到機器的 IAM 憑證。
    # 容器裡沒有任何東西需要 AWS 權限（拉模型、讀 .env 都在主機上做）。
    http_put_response_hop_limit = 1
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    project       = var.project
    region        = var.region
    repo_url      = var.repo_url
    git_ref       = var.git_ref
    models_bucket = aws_s3_bucket.models.bucket
    dotenv_param  = aws_ssm_parameter.dotenv.name
  })

  # user_data 只在第一次開機跑：準備環境、clone、第一次部署。
  # 之後改 compose 或換版本是重跑 deploy.sh，不該重建機器（重建會掉 volume 裡的資料）。
  user_data_replace_on_change = false

  lifecycle {
    # AMI 參數每次 plan 都可能拿到新版；換 AMI 會重建機器、掉資料，要升級時手動處理
    ignore_changes = [ami]
  }

  tags = {
    Name = "${var.project}-app"
  }
}

resource "aws_eip_association" "main" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.main.id
}
