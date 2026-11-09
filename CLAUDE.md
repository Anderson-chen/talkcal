- 回答語言：繁體中文
- commit、PR、issue 的寫法分別在 `.claude/rules/commit.md`、`pr.md`、`issue.md`（Claude Code 會自動載入）。
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
