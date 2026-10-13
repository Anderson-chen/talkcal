# 接下來的順序

[← 回上線準備清單](../production-readiness.md)

| 順序 | 做什麼 | 為什麼排在這裡 |
|---|---|---|
| ~~1~~ | ~~推上 GitHub、寫根目錄 README、補 CI~~ ✅ | 其他東西都要靠這三樣才看得到、才有自動把關 |
| ~~1.5~~ | ~~套用 [Branch protection](release.md#branch-protection)（選 B），建 [issue 流程](release.md#issues)~~ ✅（原本規劃 Jira，改用 GitHub Issues） | 之後每一步都從一張 issue 開始、經過 PR 合併，紀錄從第 2 步就是完整的 |
| ~~1.6~~ | ~~[整個應用接起來的系統測試](ready.md#system-test)~~ ✅ | 第 2 步要邊壓邊部署，先確定整條路接起來是通的；改動小、跟其他步驟不衝突 |
| ~~2~~ | ~~先把 k6 接進 Grafana，再驗證並修好 graceful shutdown，寫成第一份 postmortem~~ ✅ | 一個真實的問題同時練到部署、k6 和事故處理。k6 先接進 Grafana，邊壓邊部署的時候，失敗尖峰、錯誤率和部署時間點才會在同一張圖上，證據一次到位 |
| 2.5 | 零停機部署：兩個 app 輪流，先起新的、健康了再關舊的 | 第 2 步量到：graceful shutdown 修好之後，部署期間還是有 15–25 秒沒人接，等得越久空窗越長。要先起新的才補得起來 |
| 3 | 連線池面板、[一個請求從頭串到尾](run.md#request-correlation)（JDBC span、service graph、單一請求 dashboard） | 第 2 步壓測時就需要看連線池和 trace；先讓「哪一段慢」一眼看得出來，告警和 runbook 才有地方可以指 |
| 4 | SLO、告警、runbook | 讓 `ops/` 從「看得到」變成「出事會叫人」 |
| 5 | AI eval | LLM 產品特有的品質把關 |
| 6 | Rate limit、間接 prompt injection 的防線和測試 | 資安。agent 的權限已經收窄，剩下的是「塞爆 GPU」和「行程內容裡藏指令」 |
| 7 | 備份還原演練、舊資料歸檔到物件儲存 | 資料生命週期 |
| 8 | [部署到 AWS：單台 EC2 + compose](release.md#cloud-deploy)，規劃見 [infra/](../../infra/README.md) | compose 幾乎不用改就能上線，別人連得到。要先有 GPU 配額、祕密管理和成本試算 |
| 9 | [本機 k8s（kind）](release.md#k8s-local) | 上 EKS 前先在本機把 k8s 設定跑通，不花錢。如果第 2.5 步還沒做，可以提前到這裡：rolling update 直接補上部署空窗 |
| 10 | [部署到 EKS](release.md#eks) | 要等第 8、9 步和版本 tag；上去之後 staging、rollback、canary 才有地方練 |
