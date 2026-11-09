# commit 規則

- commit 和 PR 標題：`類型(範圍): 改完之後變成怎樣`，例如 `feat(alerting): 資料庫連不上、備份失敗都會寄信`。
  - 類型：`feat`、`fix`、`test`（只動測試）、`docs`、`refactor`、`ci`、`chore`；範圍用英文小寫（`auth`、`deploy`…），跨好幾塊就省略。
  - 寫改完的行為，不列做了什麼；不放 issue 編號。
- 一個 commit 只做一種類型（修 bug 和加功能分開）；驗證改動的測試跟改動放同一個 commit。
- commit 內文只寫為什麼（1～3 行），細節放 PR 描述。
- commit 訊息不要有 `Co-Authored-By: Claude`。
