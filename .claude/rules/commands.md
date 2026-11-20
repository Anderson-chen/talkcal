# 指令規則

指令都在 repo 根目錄下執行。開 PR 前照改到的地方執行哪些測試，見 [open-pr skill](../skills/open-pr/SKILL.md) 的「照改到的地方驗證」。那張表和下面這張表都沒有的腳本，先讀腳本開頭的說明再執行；每種測試要先準備什麼見 [docs/testing.md](../../docs/testing.md)。

## 先問使用者才能執行

這些會改到正在執行的服務、資料、雲端費用，或要執行很久。指令照 [justfile](../../justfile)：

| 做什麼 | 指令 |
|---|---|
| 部署、發版、退版 | `just deploy`、`just release`、`just rollback`、`just unpin`、`just watch` |
| 還原線上資料庫 | `just restore` |
| EC2 開關機 | `just ec2-start`、`just ec2-stop` |
| 壓力測試 | `just perf <腳本>`、`k6 run perf/<腳本>` |
| 告警端到端測試（約 15 分鐘） | `just alerting` |
| 推上 GitHub | `git push` |
