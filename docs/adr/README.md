# 架構決策紀錄（ADR）

每份記一個「當初為什麼這樣選」：當時的條件、考慮過哪些選項、選了什麼、代價是什麼、什麼情況下要回頭重想。
選好就不改內容；條件變了要換做法，就寫一份新的、把舊的狀態改成「被 00xx 取代」。

理由原本散在程式碼註解、migration 開頭和各目錄的 README（[#47](https://github.com/Anderson-chen/talkcal/issues/47)）。
這裡把已經做過的重大決定收在一起，每份都連回原本寫理由的那幾行，細節以那裡為準；只收 repo 裡找得到理由的。

| 編號 | 決定 | 狀態 |
|---|---|---|
| [0001](0001-eks-with-managed-node-groups.md) | 階段二跑在 EKS，節點用 managed node group（不用 ECS、不用 Auto Mode） | 已採用（[#58](https://github.com/Anderson-chen/talkcal/issues/58)） |
| [0002](0002-model-bedrock-default-gpu-optional.md) | 模型預設走 Bedrock，GPU 節點群組做成可選、平常 0 台 | 已採用（#58） |
| [0003](0003-self-hosted-observability-on-s3.md) | 觀測在叢集內自架，三個資料庫的區塊存 S3 | 已採用（#58） |
| [0004](0004-push-deploy-from-github-actions.md) | 部署由 GitHub Actions 推（`kubectl apply -k`），先不上 Argo CD | 已採用（#58） |
| [0005](0005-cloudfront-s3-waf-private-alb.md) | 前端走 CloudFront + S3，`/api` 經 VPC origin 進私有 ALB，限流從 nginx 搬到 WAF | 已採用（#58） |
| [0006](0006-spring-ai-openai-compatible.md) | 跟模型講話用 Spring AI 的 OpenAI 相容 client | 已採用 |
| [0007](0007-self-hosted-llama-cpp-qwen3-8b.md) | 模型自己架：llama.cpp 跑 Qwen3-8B（Q4_K_M），映像檔自己 build | 已採用（本機） |
| [0008](0008-own-agent-loop-and-memory.md) | AI 助理的 agent loop 自己寫，對話記憶連工具呼叫一起存 | 已採用 |
| [0009](0009-jdbc-client-not-jpa.md) | 資料庫存取用 JdbcClient 手寫 SQL，不用 JPA | 已採用 |
| [0010](0010-hexagonal-with-archunit.md) | 後端用六角架構、功能優先的目錄，規則寫成 ArchUnit 測試 | 已採用 |
| [0011](0011-wall-clock-timestamp-without-time-zone.md) | 行程時間存牆上時間：LocalDateTime + 不帶時區的 TIMESTAMP | 已採用 |
| [0012](0012-alloy-to-mimir-loki-tempo.md) | 觀測只有 Alloy 一個收集器，分送 Mimir、Loki、Tempo | 已採用 |
| [0013](0013-ci-same-script-in-container.md) | CI 的檢查只寫在 `ci/checks.sh`、在容器裡跑，本機和 GitHub 共用 | 已採用 |
| [0014](0014-local-cd-pull-watcher.md) | 本機 CD 用 pull：watcher 看 master 的 CI，綠燈才部署 | 已採用 |
| [0015](0015-aws-stage-one-ec2-compose-bedrock.md) | 上 AWS 分兩階段；階段一一台 EC2 跑同一份 compose，模型先走 Bedrock | 已採用 |

0001–0005 是做 #58（EKS）時寫的，0006 起是補記之前已經做過的決定。

## 什麼時候寫一份

- 換掉或引進一個技術元件（框架、資料庫、模型、收集器、部署方式）。
- 選了一個「之後的人會想問為什麼不用 X」的做法。
- 在 PR 裡寫了一段選型理由：理由留在程式碼註解或目錄 README 沒關係，ADR 寫摘要、連回去。

小的實作細節（參數值、命名）留在程式碼註解就好。

## 格式

```
# 編號. 標題（一句話講決定）
狀態、日期、相關 issue／commit
## 背景      —— 當時的條件、限制
## 選項      —— 每個選項的好處、代價（附查證來源和日期）
## 決定      —— 選哪個、為什麼
## 代價      —— 選了之後要承擔什麼
## 什麼時候重想 —— 哪些條件變了就要回頭
## 證據      —— repo 裡寫著理由、看得到結果的檔案
```

補記的決定，日期寫當初做決定的那天（看 commit），不是補記的那天。
