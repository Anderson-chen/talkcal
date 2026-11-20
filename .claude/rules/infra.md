---
paths:
  - "infra/**"
  - "deploy/**"
  - "ops/**"
  - "web/nginx.conf"
---

# 改基礎設施

改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
圖上的元件、依賴、參數值還對不對。要改就跟程式碼放在同一個 PR，並把頁尾的「最後核對」日期改成當天。

檔案位置（`F`、`L` 兩張表）記錨點，發佈時才換成行號，檔案多一行、少一行都不用改那頁。
錨點對不上時 `ci/checks.sh` 會紅燈並列出是哪一個，照 [lines.mjs](../../docs/architecture/lines.mjs) 開頭的寫法改；
要看換成行號的樣子，執行 `node docs/architecture/lines.mjs --out <資料夾>`，開那個資料夾裡的 `index.html`。
