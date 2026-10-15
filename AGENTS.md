- 回答語言：繁體中文
- commit 時千萬不要有Co-Authored-By: Codex 放在訊息
- 開 PR 時描述裡不要有「🤖 Generated with Codex」這類註明是 Codex 寫的字樣
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
