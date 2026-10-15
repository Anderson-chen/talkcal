- 回答語言：繁體中文
- commit 時千萬不要有Co-Authored-By: Claude 放在訊息
- 開 PR 時描述裡不要有「🤖 Generated with Claude Code」這類註明是 Claude 寫的字樣
- 改到 `infra/` 或 `deploy/` 底下的檔案時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F` 那張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁首的「最後核對」日期改成當天。
