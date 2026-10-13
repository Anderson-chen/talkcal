# 對外只開 Caddy 的 80/443。app（18090）、模型（8080）、資料庫（5432）、Grafana（3000）
# 在 compose 裡本來就只綁 127.0.0.1，這裡再擋一層：就算哪天綁錯，外面也連不到。
resource "aws_security_group" "app" {
  name        = "${var.project}-app"
  description = "Single-host docker compose stack: only Caddy is reachable"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project}-app"
  }
}

# 80 不只是轉址到 443：Let's Encrypt 的 HTTP-01 驗證走這個埠，關掉就拿不到憑證
resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP (redirect + ACME HTTP-01 challenge)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# Caddy 預設也開 HTTP/3（QUIC 走 UDP 443）；不開這條瀏覽器會自動退回 TCP，只是少了 HTTP/3
resource "aws_vpc_security_group_ingress_rule" "https_quic" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS over QUIC (HTTP/3)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "udp"
}

# 預設關閉：登入機器走 SSM Session Manager。真的需要時 -var="allow_ssh=true" -var="ssh_cidr=..."
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  count = var.allow_ssh ? 1 : 0

  security_group_id = aws_security_group.app.id
  description       = "SSH from developer workstation"
  cidr_ipv4         = var.ssh_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"

  lifecycle {
    precondition {
      condition     = var.ssh_cidr != null
      error_message = "allow_ssh = true 時要給 ssh_cidr，例如 -var=\"ssh_cidr=203.0.113.5/32\"。"
    }
  }
}

# 出網全開：要拉 Docker Hub / GitHub 的映像檔和原始碼、S3 的模型檔、Let's Encrypt 的憑證
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "all outbound"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
