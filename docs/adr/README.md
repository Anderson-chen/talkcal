# 架構決策紀錄（ADR）

每份記一個「當初為什麼這樣選」：當時的條件、考慮過哪些選項、選了什麼、代價是什麼、什麼情況下要回頭重想。
選好就不改內容；條件變了要換做法，就寫一份新的、把舊的狀態改成「被 00xx 取代」。

整套慣例（格式、什麼時候該寫）在 [#47](https://github.com/Anderson-chen/talkcal/issues/47) 定案。這裡先放 [#58](https://github.com/Anderson-chen/talkcal/issues/58)
做階段二時決定的幾項（原本列在 [infra/envs/2-eks/README.md](../../infra/envs/2-eks/README.md) 的「待決定」）。

| 編號 | 決定 | 狀態 |
|---|---|---|
| [0001](0001-eks-with-managed-node-groups.md) | 階段二跑在 EKS，節點用 managed node group（不用 ECS、不用 Auto Mode） | 已採用 |
| [0002](0002-model-bedrock-default-gpu-optional.md) | 模型預設走 Bedrock，GPU 節點群組做成可選、平常 0 台 | 已採用 |
| [0003](0003-self-hosted-observability-on-s3.md) | 觀測在叢集內自架，三個資料庫的區塊存 S3 | 已採用 |
| [0004](0004-push-deploy-from-github-actions.md) | 部署由 GitHub Actions 推（`kubectl apply -k`），先不上 Argo CD | 已採用 |
| [0005](0005-cloudfront-s3-waf-private-alb.md) | 前端走 CloudFront + S3，`/api` 經 VPC origin 進私有 ALB，限流從 nginx 搬到 WAF | 已採用 |

## 格式

```
# 編號. 標題（一句話講決定）
狀態、日期、相關 issue
## 背景      —— 當時的條件、限制
## 選項      —— 每個選項的好處、代價（附查證來源和日期）
## 決定      —— 選哪個、為什麼
## 代價      —— 選了之後要承擔什麼
## 什麼時候重想 —— 哪些條件變了就要回頭
```
