- 回答語言：繁體中文
- commit 與 開 PR 時禁止出現Co-Authored-By: Claude 或 Generated with Claude Code 的字眼
- commit 和 PR 標題：`類型(範圍): 改完之後變成怎樣`，例如 `feat(alerting): 資料庫連不上、備份失敗都會寄信`。
  - 類型：`feat`、`fix`、`test`（只動測試）、`docs`、`refactor`、`ci`、`chore`；範圍用英文小寫（`auth`、`deploy`…），跨好幾塊就省略。
  - 寫改完的行為，不列做了什麼；不放 issue 編號。
  - 一個 commit 只做一種類型（修 bug 和加功能分開）；驗證改動的測試跟改動放同一個 commit。
  - commit 內文只寫為什麼（1～3 行），細節放 PR 描述。
- PR 描述照 `.github/pull_request_template.md`：大標題用英文（`Summary`、`Depends On`、`Evidence`、`Test Plan`、`Merge Danger`），
  內文用中文，不適用的段落整段拿掉。演練時間軸這類很長的紀錄收進 `<details>`。
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
