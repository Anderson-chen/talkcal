# issue 規則

- issue 標題：寫做完之後變成怎樣，跟 PR 標題同一種寫法（見 `commit.md`）但不加 `類型(範圍):`（類型等開 PR 時再決定），PR 標題才能直接從它改寫。
  - 問題寫看得到的症狀，例如 `app 換 IP 後 nginx 還連舊的，API 回 502`。
  - epic 寫 `epic(範圍): 名稱`，例如 `epic(run): 上線後觀測`；範圍有對應的 `stage:`、`product:` label 就用同一個字。
  - 網頁上開 issue 選 `.github/ISSUE_TEMPLATE/` 的模板（有寫入權限的人選單裡還有空白 issue，不要選）。
- issue 內文：照對應模板的欄位，用 `### 欄位名稱` 分段（名稱跟模板的 `label` 一樣，是英文，例如 `Goal`、`Acceptance Criteria`），內文用中文，沒內容的欄位寫 `_No response_`。
  網頁表單產生的就是這個格式而且改不了；`gh issue create` 開的不經過模板，要自己照這個格式寫，
  模板的 `labels`、`projects` 也用 `--label`、`--project` 帶上，兩邊才一致（`--project` 要看板名稱 `talkcal-to-product`，不是模板寫的 `Anderson-chen/6`）。
  - 模板裡 `required: true` 的欄位，CLI 開的也不能寫 `_No response_`。
  - `Acceptance Criteria`（做完的證據）一條一個 `- [ ]`，每條都能判斷過或沒過，反例也各寫一條；做完一條勾一條，看 issue 就知道還剩幾條。
  - 卡在別張 issue 時設 blocked by 關係，內文不另外寫依賴；issue 標題下方會顯示 `Blocked by` 和在等哪一張。
    - 網頁：issue 右側的 `Relationships`。
    - CLI（Bash）：`gh api -X POST repos/{owner}/{repo}/issues/<被卡住的編號>/dependencies/blocked_by -F issue_id=$(gh api repos/{owner}/{repo}/issues/<卡住它的編號> -q .id)`（`issue_id` 要的是內部 id，不是編號）。
  - 欄位以外的小段落（參考資料、刻意不做…）用 `####` 放在最接近的欄位底下，不另外開 `###`，欄位才對得上模板。
