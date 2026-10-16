# ── EKS 控制平面（ADR 0001）────────────────────────────────────────────────────
# 不用 terraform-aws-modules/eks：跟 1-ec2 一樣直接寫資源，叢集由哪些東西組成一眼看得到。
resource "aws_iam_role" "cluster" {
  name = "${local.name}-eks-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "main" {
  name     = local.name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids = aws_subnet.private[*].id
    # API 端點：私有（節點走 VPC 內部）+ 公開（本機 kubectl、GitHub Actions 要連）。
    # 公開端點靠 IAM 認證擋；要再收緊可以設 public_access_cidrs，但 GitHub runner 的 IP 不固定
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = var.cluster_public_access_cidrs
  }

  access_config {
    # 只用 access entry（EKS API）管誰能進叢集，不用舊的 aws-auth ConfigMap：改權限是一般的 AWS API，有 CloudTrail 紀錄
    authentication_mode = "API"
    # 跑 terraform apply 的這個身分自動拿到叢集管理員，helm、kubernetes provider 才裝得了東西
    bootstrap_cluster_creator_admin_permissions = true
  }

  # 控制平面的 log 每份都按量收 CloudWatch 費用，只開稽核（誰改了什麼）和認證
  enabled_cluster_log_types = ["audit", "authenticator"]

  # Kubernetes 的 Secret 不另外設 encryption_config（自管 KMS key）：EKS 1.28 起所有 API 資料預設就用 AWS 的金鑰做信封加密。
  # 自管 key 每月 1 USD，換來的是「能自己停用、稽核金鑰」，作品集用不到（Trivy AWS-0039，README「靜態檢查」）

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

# ── 一般節點 ─────────────────────────────────────────────────────────────────
resource "aws_iam_role" "node" {
  name = "${local.name}-eks-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# 節點本身只要這三個：加入叢集、VPC CNI 管網卡、從 ECR 拉映像檔。
# Pod 要的 AWS 權限（讀祕密、註冊 ALB target）不放在節點上，用 Pod Identity 個別給（pod-identity.tf）
resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly",
    # 不開 22，進節點走 SSM Session Manager（跟 1-ec2 一樣）
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "general" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "general"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = aws_subnet.private[*].id

  ami_type       = "AL2023_x86_64_STANDARD"
  instance_types = [var.node_instance_type]
  capacity_type  = var.node_capacity_type
  disk_size      = 30

  scaling_config {
    desired_size = var.node_count
    min_size     = var.node_count
    max_size     = var.node_count + 1 # 換節點（升版）時多開一台，先起新的再關舊的
  }

  update_config {
    max_unavailable = 1
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ── GPU 節點（model_backend = gpu，ADR 0002）──────────────────────────────────
# Bottlerocket NVIDIA：驅動和 NVIDIA device plugin 都內建，節點一起來就回報 nvidia.com/gpu: 1，
# 不必自己裝 GPU Operator。AL2023 的 NVIDIA AMI 有驅動但 device plugin 要另外裝。
resource "aws_eks_node_group" "gpu" {
  count           = local.gpu ? 1 : 0
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "gpu"
  node_role_arn   = aws_iam_role.node.arn
  # 只放有這個機型的可用區（locals.azs 在 gpu 時就是從有 g6 的可用區挑的）
  subnet_ids = aws_subnet.private[*].id

  ami_type       = "BOTTLEROCKET_x86_64_NVIDIA"
  instance_types = [var.gpu_instance_type]
  capacity_type  = "ON_DEMAND"
  # llama-server 的 CUDA 映像檔幾 GB，加上模型檔走 PVC（EBS），節點本身不放模型
  disk_size = 80

  scaling_config {
    desired_size = var.gpu_node_count
    min_size     = 0
    max_size     = max(var.gpu_node_count, 1)
  }

  # 一般 Pod 不要排到很貴的 GPU 節點上：只有帶對應 toleration 的 llm-chat 會上來
  taint {
    key    = "nvidia.com/gpu"
    value  = "true"
    effect = "NO_SCHEDULE"
  }

  labels = {
    "talkcal/node-role" = "gpu"
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ── add-on：EKS 代管版本，跟叢集一起升 ─────────────────────────────────────────
# 不寫 addon_version：用這個 Kubernetes 版本的預設版本。要釘死的話用
#   aws eks describe-addon-versions --kubernetes-version 1.37 --addon-name vpc-cni
locals {
  addons = ["vpc-cni", "kube-proxy", "coredns", "eks-pod-identity-agent"]
}

resource "aws_eks_addon" "core" {
  for_each     = toset(local.addons)
  cluster_name = aws_eks_cluster.main.name
  addon_name   = each.value

  # coredns 要有節點才能排上去，其他的是 DaemonSet 或在節點加入前就要有
  depends_on = [aws_eks_node_group.general]
}

# EBS CSI：PVC（gpu 時放模型檔、觀測的暫存）要用。權限用 Pod Identity，不掛在節點上
resource "aws_eks_addon" "ebs_csi" {
  cluster_name = aws_eks_cluster.main.name
  addon_name   = "aws-ebs-csi-driver"

  pod_identity_association {
    role_arn        = aws_iam_role.pod["ebs-csi"].arn
    service_account = "ebs-csi-controller-sa"
  }

  depends_on = [aws_eks_addon.core]
}

# ── ALB 要能連到 app 的 Pod ────────────────────────────────────────────────
# managed node group 沒給 launch template 時，節點（和 Pod，VPC CNI 讓 Pod 用節點的網卡安全群組）
# 用的是 EKS 自動建的叢集安全群組。只開 ALB → 8090
resource "aws_vpc_security_group_ingress_rule" "alb_to_app" {
  security_group_id            = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8090
  to_port                      = 8090
  description                  = "ALB to app pods"
}
