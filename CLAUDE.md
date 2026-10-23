- 回答語言：繁體中文
- commit 與 開 PR 時禁止出現Co-Authored-By: Claude 或 Generated with Claude Code 的字眼
- commit 和 PR 標題：`類型(範圍): 改完之後變成怎樣`，例如 `feat(alerting): 資料庫連不上、備份失敗都會寄信`。
  - 類型：`feat`、`fix`、`test`（只動測試）、`docs`、`refactor`、`ci`、`chore`；範圍用英文小寫（`auth`、`deploy`…），跨好幾塊就省略。
  - 寫改完的行為，不列做了什麼；不放 issue 編號。
  - 一個 commit 只做一種類型（修 bug 和加功能分開）；驗證改動的測試跟改動放同一個 commit。
  - commit 內文只寫為什麼（1～3 行），細節放 PR 描述。
- issue 標題：寫做完之後變成怎樣，跟 PR 標題同一種寫法但不加 `類型(範圍):`（類型等開 PR 時再決定），PR 標題才能直接從它改寫。
  - 問題寫看得到的症狀，例如 `app 換 IP 後 nginx 還連舊的，API 回 502`。
  - epic 寫 `epic(範圍): 名稱`，例如 `epic(run): 上線後觀測`；範圍有對應的 `stage:`、`product:` label 就用同一個字。
  - 網頁上開 issue 選 `.github/ISSUE_TEMPLATE/` 的模板（有寫入權限的人選單裡還有空白 issue，不要選）；
    用 `gh issue create --title --body` 開的不經過模板，要自己照這裡寫。
- issue 內文：照對應模板的欄位，用 `### 欄位名稱` 分段（名稱跟模板的 `label` 一樣），沒內容的欄位寫 `_No response_`。
  網頁表單產生的就是這個格式而且改不了，CLI 開的跟著它，兩邊才一致。
  - 「做完的證據」一條一個 `- [ ]`，每條都能判斷過或沒過，反例也各寫一條；做完一條勾一條，看 issue 就知道還剩幾條。
    一般 issue 的這一欄不能寫 `_No response_`（網頁表單是必填）；問題回報還沒查到原因可以先空著。
  - 卡在別張 issue 時，設 blocked by 關係，內文不另外寫依賴；issue 標題下方會顯示 `Blocked by` 和在等哪一張。
    網頁在 issue 右側的 `Relationships` 設；CLI 用 `gh api -X POST repos/{owner}/{repo}/issues/<被卡住的>/dependencies/blocked_by -F issue_id=<卡住它的 issue 的 id>`（`id` 不是編號，用 `gh api repos/{owner}/{repo}/issues/<編號> -q .id` 查）。
  - 欄位以外的小段落（參考資料、刻意不做…）用 `####` 放在最接近的欄位底下，不另外開 `###`，欄位才對得上模板。
- PR 描述照 `.github/pull_request_template.md`：大標題用英文（`Summary`、`Depends On`、`Evidence`、`Test Plan`、`Merge Danger`），
  內文用中文，不適用的段落整段拿掉。演練時間軸這類很長的紀錄收進 `<details>`。
- 開 PR 之前先做一輪獨立審查，審這個分支相對 `master` 的 diff：
  - 每次都跑 `/code-review`（找 bug；文件寫錯路徑、說錯行為也算）。改到程式碼（含腳本、workflow）時，
    先跑 `/simplify`（找重複、可以簡化的地方，會直接改）再跑 `/code-review`，`/code-review` 才審得到最後進 PR 的那一版。
  - 找到的問題在同一個分支修掉；不修的，在 PR 裡寫為什麼。
  - 結果寫成 PR 描述 `Evidence` 的一點：跑了哪個、審到哪個 commit、找到幾個、怎麼處理。之後 push 又改了程式碼就重跑，更新這一點。
  - 理由：寫的人重看自己的 diff，會沿用寫的時候的假設；先讓審查指令過一輪，人審的時候看得到已經抓過什麼、剩下什麼要自己判斷。
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
