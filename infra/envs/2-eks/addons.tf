# ── 叢集裡的平台元件：apply 完就能部署 app ────────────────────────────────────
# 只裝「平台」：ALB controller、External Secrets、namespace、部署用的權限。
# app 本身不在這裡，由 GitHub Actions 部署（ADR 0004）；觀測那組等 #56 的 ops/k8s 定下來再補（ADR 0003）。
#
# chart 版本釘死（2026-10-16 查的最新版）：helm 不會自己升，安全修補要回來改這裡。

# AWS Load Balancer Controller：只用它的 TargetGroupBinding，把 app Pod 的 IP 註冊進 alb.tf 的 target group。
# 不用它建 ALB（Ingress / Gateway API）：見 alb.tf 開頭
resource "helm_release" "aws_load_balancer_controller" {
  name       = "aws-load-balancer-controller"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = "3.6.0"
  namespace  = "kube-system"

  set = [
    { name = "clusterName", value = aws_eks_cluster.main.name },
    { name = "region", value = var.region },
    { name = "vpcId", value = aws_vpc.main.id },
    # ServiceAccount 名稱要跟 pod-identity.tf 的 association 一樣
    { name = "serviceAccount.name", value = "aws-load-balancer-controller" },
  ]

  # Pod 起來之前 association 就要在，不然 controller 拿不到憑證、一直重啟
  depends_on = [aws_eks_pod_identity_association.pod, aws_eks_addon.core]
}

# External Secrets Operator：把 Secrets Manager 的 talkcal/db、talkcal/app 同步成 k8s Secret（ADR 0001、#14）。
# 祕密不進 git、不進 manifest；Pod 看到的是一般的 k8s Secret，跟 kind 上手動建的同名（#56）
resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "2.12.0"
  namespace        = "external-secrets"
  create_namespace = true

  set = [
    { name = "installCRDs", value = "true" },
    { name = "serviceAccount.name", value = "external-secrets" },
  ]

  depends_on = [aws_eks_pod_identity_association.pod, aws_eks_addon.core]
}

# app 的 namespace 由 Terraform 建，overlay 把 base 裡的 Namespace 拿掉：
# 部署角色只有這個 namespace 裡的權限，建不了 cluster 層級的 Namespace（ADR 0004）
resource "kubernetes_namespace_v1" "talkcal" {
  metadata {
    name = "talkcal"
    labels = {
      "app.kubernetes.io/part-of" = "talkcal"
      # Pod Security Admission：擋 privileged、hostPath 這類（baseline）；更嚴的 restricted 先只警告。
      # app 在 overlay 裡已經符合 restricted，但 #56 的 llm-chat（gpu）的 initContainer 還沒驗過，先不直接擋
      "pod-security.kubernetes.io/enforce" = "baseline"
      "pod-security.kubernetes.io/warn"    = "restricted"
      "pod-security.kubernetes.io/audit"   = "restricted"
      # ALB controller 幫這個 namespace 的 Pod 加 readiness gate：新 Pod 要等 ALB 健康檢查也過了才算 ready，
      # rolling update 才去關下一份舊的（overlays/eks/target-group-binding.yaml）
      "elbv2.k8s.aws/pod-readiness-gate-inject" = "enabled"
    }
  }
}

# ── GitHub Actions 部署角色在叢集裡能做什麼 ─────────────────────────────────────
# access entry（github.tf）把 IAM 角色對應到群組 talkcal-deployers；這裡決定群組的權限：
# 只在 talkcal namespace 裡，內建的 edit（Deployment、Service、Secret…）加上兩種 CRD。
resource "kubernetes_cluster_role_v1" "deployer_crds" {
  metadata {
    name = "talkcal-deployer-crds"
  }

  rule {
    api_groups = ["external-secrets.io"]
    resources  = ["externalsecrets", "secretstores"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }

  rule {
    api_groups = ["elbv2.k8s.aws"]
    resources  = ["targetgroupbindings"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
}

resource "kubernetes_role_binding_v1" "deployer_edit" {
  metadata {
    name      = "talkcal-deployers-edit"
    namespace = kubernetes_namespace_v1.talkcal.metadata[0].name
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = "edit"
  }
  subject {
    kind      = "Group"
    name      = "talkcal-deployers"
    api_group = "rbac.authorization.k8s.io"
  }
}

resource "kubernetes_role_binding_v1" "deployer_crds" {
  metadata {
    name      = "talkcal-deployers-crds"
    namespace = kubernetes_namespace_v1.talkcal.metadata[0].name
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = kubernetes_cluster_role_v1.deployer_crds.metadata[0].name
  }
  subject {
    kind      = "Group"
    name      = "talkcal-deployers"
    api_group = "rbac.authorization.k8s.io"
  }
}

# gp3 的 StorageClass（EKS 預設只有 gp2）：gpu 時放模型檔的 PVC 用（overlays/eks-gpu）。
# StorageClass 是 cluster 層級的，部署角色建不了，所以放這裡。gp3 比 gp2 便宜約 20%、IOPS 不綁容量
resource "kubernetes_storage_class_v1" "gp3" {
  metadata {
    name = "gp3"
  }
  storage_provisioner    = "ebs.csi.aws.com"
  reclaim_policy         = "Delete"
  volume_binding_mode    = "WaitForFirstConsumer" # 等 Pod 排到哪一區再建 EBS，不然可能建在沒有 GPU 節點的那區
  allow_volume_expansion = true
  parameters = {
    type      = "gp3"
    encrypted = "true"
  }

  depends_on = [aws_eks_addon.ebs_csi]
}
