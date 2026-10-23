# 0001. 階段二跑在 EKS，節點用 managed node group

- 狀態：已採用
- 日期：2026-10-16
- 相關：[#58 部署到 EKS](https://github.com/Anderson-chen/talkcal/issues/58)、[#56 本機 kind](https://github.com/Anderson-chen/talkcal/issues/56)、epic [#17](https://github.com/Anderson-chen/talkcal/issues/17)

## 背景

階段一（`infra/envs/1-ec2`）是一台 EC2 跑 compose：換版時有空窗、一台掛了全停。階段二要的是「換版不停機、一台掛了不會全停」，
而且 staging（[#24](https://github.com/Anderson-chen/talkcal/issues/24)）、rollback（[#19](https://github.com/Anderson-chen/talkcal/issues/19)）、
canary（[#20](https://github.com/Anderson-chen/talkcal/issues/20)）要有地方練。
本機的下一步（#56）已經是 kind + kustomize（`deploy/k8s/base` + `overlays/`），探針、preStop、rolling update 都在那裡寫好了。

這是給面試官看的作品集：流量很低、平常不開，**固定月費**比效能重要。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| ECS（Fargate 或 EC2） | 沒有控制平面月費；AWS 原生、元件少 | #56 寫好的 k8s manifest 不能用，要另寫 task definition；rolling update、探針、canary 的語彙都不同，本機練的搬不上來 |
| EKS + managed node group | 跟 kind 同一套 manifest（只差 overlay）；節點、add-on 都是看得到的 Terraform 資源 | 控制平面 0.10 USD/h（約 73 USD/月）；版本落後到 extended support 變 0.60 USD/h，要記得升 |
| EKS Auto Mode | 節點、負載平衡、EBS CSI、Pod Identity agent 都由 AWS 管；少很多 add-on | 每台節點加收管理費（第三方整理約 EC2 隨選價的 12%）；節點生命週期被藏起來，作品集想展示的東西反而看不到 |
| 自己裝 k8s（kubeadm、k3s on EC2） | 最便宜 | 控制平面自己顧，跟「上線後的維運認知」這個目的無關 |

2026 年的資料：

- EKS 控制平面標準支援 0.10 USD/h、延伸支援 0.60 USD/h（[cloudburn：EKS Pricing in 2026](https://cloudburn.io/blog/amazon-eks-pricing)、[CloudZero](https://www.cloudzero.com/blog/aws-eks-pricing/)）。
- 小團隊的帳單裡，控制平面通常不是大頭，節點、NAT、Load Balancer、跨 AZ 流量才是（[Qovery](https://www.qovery.com/blog/aks-vs-eks-small-devops-team-cost-and-day-2-burden)、[FactualMinds](https://www.factualminds.com/blog/aws-ecs-vs-eks-container-orchestration-decision-guide/)）。
- EKS 目前最新是 Kubernetes 1.37（2026-08 釋出，[EKS 標準支援版本](https://docs.aws.amazon.com/eks/latest/userguide/kubernetes-versions-standard.html)）。

## 決定

**EKS，Kubernetes 1.37，節點用 managed node group，Pod 拿 AWS 權限用 EKS Pod Identity。**

- 選 EKS 不選 ECS：#56 的 manifest 原樣上去，overlay 只換「環境不同的地方」，這正是要展示的「本機驗證過的東西搬得上雲端」。
- 選 managed node group 不選 Auto Mode：Auto Mode 省的是維運時間，但這個專案的目的就是把維運的每一塊攤開（節點、CNI、EBS CSI、ALB controller 各是一個 Terraform 資源，看得到、改得到）。
  多 12% 的管理費倒是小事。
- Pod Identity 不用 IRSA：不必建 OIDC provider、trust policy 不必寫死叢集的 OIDC 網址；terraform-aws-modules/eks v21 也已經預設用 Pod Identity。
- Terraform 直接寫 `aws_eks_*` 資源，不用 terraform-aws-modules/eks：跟 1-ec2 同一個風格，每個資源旁邊寫為什麼。模組省行數，但把「叢集由哪些東西組成」藏起來了。

## 代價

- 固定月費變高：控制平面 + NAT + ALB 每月約 140 USD 起跳，**開著就在收**（明細見 [2-eks README 的成本估算](../../infra/envs/2-eks/README.md#成本估算)）。
  所以階段二的用法是「要展示、要練 rollout 時 apply，練完 destroy」，不是常駐。
- 每年要升三次 Kubernetes 版本，不然掉進 extended support（6 倍價）。
- add-on（ALB controller、External Secrets）的版本要自己追；Auto Mode 不必。

## 什麼時候重想

- 確定不需要 k8s 的語彙（例如放棄 canary）→ ECS 省 73 USD/月。
- 節點、add-on 的維運佔掉太多時間 → Auto Mode。
- 要常駐上線 → 先算 Savings Plan、Spot，或回到 1-ec2。
