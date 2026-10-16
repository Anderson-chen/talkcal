# ── Pod Identity：每個需要 AWS 權限的 ServiceAccount 各一個角色（ADR 0001）────────
# 節點的角色只能加入叢集、拉映像檔；誰要讀祕密、誰要註冊 ALB target，個別給、個別收。
# 比 IRSA 少一個 OIDC provider，trust policy 也不必寫死叢集的 OIDC 網址（換叢集不用改）。
locals {
  pod_roles = merge(
    {
      # AWS Load Balancer Controller：把 app Pod 的 IP 註冊進 Terraform 建的 target group（TargetGroupBinding）
      "aws-load-balancer-controller" = {
        namespace        = "kube-system"
        service_accounts = ["aws-load-balancer-controller"]
        managed_policies = []
        # 官方發的政策原樣放進來（v3.6.0 的 docs/install/iam_policy.json），升 chart 時一起換
        policy = file("${path.module}/policies/aws-load-balancer-controller.json")
      }
      # External Secrets：只讀 talkcal/ 底下的兩份祕密
      "external-secrets" = {
        namespace        = "external-secrets"
        service_accounts = ["external-secrets"]
        managed_policies = []
        policy = jsonencode({
          Version = "2012-10-17"
          Statement = [{
            Effect   = "Allow"
            Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
            Resource = [aws_secretsmanager_secret.db.arn, aws_secretsmanager_secret.app.arn]
          }]
        })
      }
      "ebs-csi" = {
        namespace        = "kube-system"
        service_accounts = [] # 由 aws_eks_addon 的 pod_identity_association 綁，不在這裡建
        managed_policies = ["arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"]
        policy           = null
      }
      # 觀測三件套（ADR 0003）：各自的 bucket 讀寫。ServiceAccount 名稱跟 #56 的 ops/k8s 對齊，不同的話改這裡
      "observability" = {
        namespace        = "talkcal-ops"
        service_accounts = ["mimir", "loki", "tempo"]
        managed_policies = []
        policy = jsonencode({
          Version = "2012-10-17"
          Statement = [
            {
              Effect   = "Allow"
              Action   = ["s3:ListBucket"]
              Resource = [for k in ["mimir", "loki", "tempo"] : aws_s3_bucket.this[k].arn]
            },
            {
              Effect   = "Allow"
              Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
              Resource = [for k in ["mimir", "loki", "tempo"] : "${aws_s3_bucket.this[k].arn}/*"]
            },
          ]
        })
      }
    },
    local.gpu ? {
      # llm-chat 的 initContainer 從 S3 拉模型檔進 PVC（ADR 0002）
      "llm-chat" = {
        namespace        = "talkcal"
        service_accounts = ["llm-chat"]
        managed_policies = []
        policy = jsonencode({
          Version = "2012-10-17"
          Statement = [
            { Effect = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.this["models"].arn] },
            { Effect = "Allow", Action = ["s3:GetObject"], Resource = ["${aws_s3_bucket.this["models"].arn}/*"] },
          ]
        })
      }
    } : {},
  )

  # 角色 × ServiceAccount 攤平，一筆一個 association
  pod_associations = merge([
    for role, r in local.pod_roles : {
      for sa in r.service_accounts : "${role}/${sa}" => { role = role, namespace = r.namespace, sa = sa }
    }
  ]...)
}

resource "aws_iam_role" "pod" {
  for_each = local.pod_roles
  name     = "${local.name}-pod-${each.key}"

  # Pod Identity 的固定 trust：只有 EKS 的 Pod Identity agent 能扮演。哪個 Pod 拿得到，由下面的 association 決定
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy" "pod" {
  for_each = { for k, r in local.pod_roles : k => r if r.policy != null }
  name     = each.key
  role     = aws_iam_role.pod[each.key].id
  policy   = each.value.policy
}

resource "aws_iam_role_policy_attachment" "pod" {
  for_each = merge([
    for k, r in local.pod_roles : { for p in r.managed_policies : "${k}/${p}" => { role = k, policy = p } }
  ]...)
  role       = aws_iam_role.pod[each.value.role].name
  policy_arn = each.value.policy
}

resource "aws_eks_pod_identity_association" "pod" {
  for_each        = local.pod_associations
  cluster_name    = aws_eks_cluster.main.name
  namespace       = each.value.namespace
  service_account = each.value.sa
  role_arn        = aws_iam_role.pod[each.value.role].arn
}
