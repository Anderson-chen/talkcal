# ── 私有 ALB：CloudFront 的 /api/* 進叢集的唯一入口（ADR 0005）──────────────────
# 放在私有子網、沒有公開 IP：只有 CloudFront 的 VPC origin 進得來，不必再擋「繞過 CloudFront 直接打 ALB」。
# ALB、target group 由 Terraform 建（CloudFront 要在這裡就指到它）；app Pod 的 IP 由 AWS Load Balancer Controller
# 依 deploy/k8s/overlays/eks/target-group-binding.yaml 註冊進來。不用 Ingress：Ingress 建的 ALB，Terraform 拿不到它的 ARN。
resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "internal ALB for CloudFront VPC origin"
  vpc_id      = aws_vpc.main.id
}

# CloudFront VPC origin 是從 VPC 裡的網卡連進來（私有 IP），來源就是 VPC 的網段。
# ALB 本身是 internal，VPC 外面本來就連不到
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "CloudFront VPC origin"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 8090
  to_port                      = 8090
  description                  = "app pods"
}

resource "aws_lb" "api" {
  name               = "${local.name}-api"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.private[*].id

  # 連線閒置多久才斷。預設 60 秒；app 叫模型最久 2 分鐘（nginx.conf 的 proxy_read_timeout 也是 180）。
  # 實際的上限是 CloudFront 的 origin_read_timeout（var.api_origin_read_timeout），見 ADR 0005
  idle_timeout = 180

  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "app" {
  name        = "${local.name}-app"
  port        = 8090
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # 直接打 Pod 的 IP（VPC CNI），不經過 NodePort

  health_check {
    # 跟 k8s 的 readinessProbe 同一個端點：資料庫連不上時這份 app 不收流量
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }

  # Pod 要關的時候，ALB 不再送新請求，但手上的請求最多再等這麼久。
  # app 的 terminationGracePeriodSeconds 是 150（preStop 10 秒 + Spring 等請求 120 秒）：這裡比它短一點，
  # 讓 ALB 先放手，不會在 Pod 被殺之後還等著它（docs/postmortems/2026-10-12-deploy-cuts-in-flight-requests.md）
  deregistration_delay = 130
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP" # CloudFront → ALB 走 AWS 內部網路（VPC origin），使用者那段是 CloudFront 的 HTTPS

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
