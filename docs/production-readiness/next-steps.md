# 接下來的順序

[← 回上線準備清單](../production-readiness.md)

| 順序 | 做什麼 | 為什麼排在這裡 |
|---|---|---|
| ~~1~~ | ~~推上 GitHub、寫根目錄 README、補 CI~~ ✅ | 其他東西都要靠這三樣才看得到、才有自動把關 |
| 2 | 先把 k6 接進 Grafana，再驗證並修好 graceful shutdown，寫成第一份 postmortem | 一個真實的問題同時練到部署、k6 和事故處理。k6 先接進 Grafana，邊壓邊部署的時候，失敗尖峰、錯誤率和部署時間點才會在同一張圖上，證據一次到位 |
| 3 | 連線池面板、[一個請求從頭串到尾](run.md#request-correlation)（JDBC span、service graph、單一請求 dashboard） | 第 2 步壓測時就需要看連線池和 trace；先讓「哪一段慢」一眼看得出來，告警和 runbook 才有地方可以指 |
| 4 | SLO、告警、runbook | 讓 `ops/` 從「看得到」變成「出事會叫人」 |
| 5 | AI eval | LLM 產品特有的品質把關 |
| 6 | Rate limit、間接 prompt injection 的防線和測試 | 資安。agent 的權限已經收窄，剩下的是「塞爆 GPU」和「行程內容裡藏指令」 |
| 7 | 備份還原演練、舊資料歸檔到物件儲存 | 資料生命週期 |

**Jira 流程**沒有排進去，因為它取決於 [Branch protection](release.md#branch-protection) 選 A 還是 B（還沒決定）。選了 B，就在開始第 2 步之前先把 Jira 和 PR 流程建起來，之後每一步都從一張 issue 開始。
