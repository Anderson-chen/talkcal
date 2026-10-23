# 0003. 觀測在叢集內自架，三個資料庫的區塊存 S3

- 狀態：已採用
- 日期：2026-10-16
- 相關：[#58](https://github.com/Anderson-chen/talkcal/issues/58)、epic [#30](https://github.com/Anderson-chen/talkcal/issues/30)、`ops/`

## 背景

本機和 1-ec2 都是同一份 `ops/`：Alloy 收 → Mimir（指標）、Loki（log）、Tempo（trace）→ Grafana。
儀表板、告警規則都建在這套上面。上 EKS 要決定繼續自架，還是換託管。

## 選項

| 選項 | 錢 | 好處 | 代價 |
|---|---|---|---|
| 叢集內自架，區塊存 S3 | S3 幾 GB，每月不到 1 USD；吃節點記憶體（約 3–4 GB） | 跟本機同一套設定，只換 storage 那段；儀表板一行不改 | 要自己顧升版、容量；節點要開大一點 |
| Amazon Managed Prometheus + Managed Grafana | AMP 按 sample 計；AMG 每個編輯者每月約 9 USD | 不必顧資料庫 | 只有指標，log、trace 要另外找（CloudWatch、X-Ray）；儀表板要改查詢；三種訊號散在三個服務 |
| Grafana Cloud 免費層 | 0（10k 指標序列、log/trace 各 50 GB、保留 14 天） | 三種訊號都有、不佔叢集資源 | 資料出 AWS；超過免費額度就要付費；設定從自架改成推到外部 |

查證（2026）：Grafana Cloud 免費層 10k series、14 天保留（[costbench，2026-08 對過官方價目](https://costbench.com/compare/grafana-cloud-vs-prometheus/)）；
AMP 的單價各家整理差很多，要以 AWS 官方頁為準（[cubeapm](https://cubeapm.com/blog/prometheus-pricing-and-review/)）。

## 決定

**自架。Terraform 建三個 S3 bucket（mimir、loki、tempo）和一個 Pod Identity 角色，給 `talkcal-ops` namespace 裡的三個 ServiceAccount 讀寫。**

理由：這個專案要證明的是「本機那套搬得動」。換託管等於重寫儀表板和查詢，證明的是另一件事。
錢的差別很小（S3 幾乎不收錢；記憶體算在節點裡，見成本估算），而且平常 destroy，不會有長期的保存費。

Helm values（或 #56 的 `ops/k8s` 加 EKS overlay）不在這個 PR：要等 #56 把 kind 上的觀測那組定下來，再補 storage 換成 S3 的 overlay。

## 代價

- 節點要從 t3.medium（4 GB）加到 t3.large（8 GB）×2，每月多約 80 USD（開著的話）。
- Mimir、Loki、Tempo 的升版自己顧。

## 什麼時候重想

- 想讓 EKS 常駐、又不想把節點開大 → Grafana Cloud 免費層（Alloy 改推遠端，其他不動）。
- 需要長期保存或多叢集 → 託管。
