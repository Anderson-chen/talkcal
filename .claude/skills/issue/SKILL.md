---
name: issue
description: 開 issue、改現有的 issue 時使用（工作流程第 1 步，由主 session 執行）。先進 plan mode 問清楚，使用者核准 issue 草稿後才執行 `gh issue create`。內容是 issue 的標題、欄位、驗收條件、label 怎麼寫，以及 issue 和 PR 各寫什麼。
---

# issue

主 session 是使用者正在對話的那個 session。修改者、審核者、投票者都讀不到這段對話，只讀得到 issue；對話裡沒問清楚的，修改者只能猜，而 subagent 問不了使用者。所以要問使用者的事，都在這一步問完。

## 步驟

1. 用 `EnterPlanMode` 進 plan mode，讀相關的程式碼和文件。
2. 用 `AskUserQuestion` 問使用者，問到這兩件事都做到才停：
   - 模板的每個欄位都寫得出來，不用猜。
   - 每條驗收條件都能判斷過或沒過，反例也問到。
   - 讀程式碼、文件查得到的不問。
3. 照下面的寫法寫好 issue 草稿，當成 plan 交給 `ExitPlanMode`。使用者核准後才執行 `gh issue create`（改現有的 issue 用 `gh issue edit`）。使用者在對話裡另外交代的事，也寫進 issue。

## 寫法

- 讀完 issue 標題和前兩個欄位（Task 是「目標」「現況」，Bug 是「症狀」「預期」），就要知道哪裡有問題、做完會怎樣。用字照 `writing.md`。
- issue 標題：寫做完之後變成怎樣，跟 PR 標題同一種寫法（見 [commit skill](../commit/SKILL.md)）但不加 `類型(範圍):`（類型等開 PR 時再決定），PR 標題才能直接從它改寫。
  - 問題寫看得到的症狀，例如 `app 換 IP 後 nginx 還連舊的，API 回 502`。
  - epic 寫 `epic(範圍): 名稱`，例如 `epic(run): 上線後觀測`；範圍有對應的 `stage:`、`product:` label 就用同一個字。
  - 網頁上開 issue 選 `.github/ISSUE_TEMPLATE/` 的模板（有寫入權限的人選單裡還有空白 issue，不要選）。
- issue 和 PR 各寫各的，不重複：

  | | issue | PR |
  |---|---|---|
  | 什麼時候寫 | 動手前 | 改完 |
  | 寫什麼 | 為什麼要做、打算怎麼做、做完怎麼算過關（驗收條件） | 實際改了什麼、執行過的證據 |
  | 不寫 | 做完的結果（測試輸出、截圖） | 問題的背景、目標：用 `Closes #N` 連到 issue |

  做到一半發現做法跟 issue 寫的不同，PR 寫實際的做法和為什麼改，issue 的「做法」不回頭改。
- issue 內文：照對應模板的欄位，用 `### 欄位名稱` 分段（名稱跟模板的 `label` 一樣，例如 `目標`、`驗收條件`），每個欄位用條列（表格、有順序的步驟照原樣），沒內容的欄位寫 `_No response_`。
  網頁表單產生的就是這個格式而且改不了；`gh issue create` 開的不經過模板，要自己照這個格式寫，
  模板的 `labels`、`projects` 也用 `--label`、`--project` 帶上，兩邊才一致（`--project` 要看板名稱 `talkcal-to-product`，不是模板寫的 `Anderson-chen/6`）。
  - 模板裡 `required: true` 的欄位，CLI 開的也不能寫 `_No response_`。
  - 「驗收條件」（做完的證據）一條一個 `- [ ]`，每條都能判斷過或沒過，反例也各寫一條；做完一條勾一條，看 issue 就知道還剩幾條。
  - 卡在別張 issue 時設 blocked by 關係，內文不另外寫依賴；issue 標題下方會顯示 `Blocked by` 和在等哪一張。
    - 網頁：issue 右側的 `Relationships`。
    - CLI（Bash）：`gh api -X POST repos/{owner}/{repo}/issues/<被卡住的編號>/dependencies/blocked_by -F issue_id=$(gh api repos/{owner}/{repo}/issues/<卡住它的編號> -q .id)`（`issue_id` 要的是內部 id，不是編號）。
  - 欄位以外的小段落（參考資料、刻意不做…）用 `####` 放在最接近的欄位底下，不另外開 `###`，欄位才對得上模板。
- label：開的時候就貼好，看板和 issue 列表才篩得出來。每一種貼幾個：

  | 種類 | label | 每張 issue | 怎麼選 |
  |---|---|---|---|
  | 放在哪 | `stage: …` 或 `product: …` | 剛好一個，兩種擇一 | 照 [docs/classification.md](../../../docs/classification.md) 的「分段的依據」「面向的依據」判斷；子 issue 跟它的 epic 用同一個 |
  | 優先度 | `p0`、`p1` | 剛好一個 | `p0`：這一輪要做完；`p1`：先記下來，`p0` 做完再排 |
  | 改到哪塊 | `area: …` | 零到多個 | 會動到的技術領域，可以疊；都沒有就不貼 |
  | 特殊種類 | `epic`、`bug` | 是才貼 | Epic、Bug 模板會自動帶 |

  - 「放在哪」選不出來，先回頭看 docs/classification.md 的「跟上線準備怎麼分」：要問工程才能決定的放 `stage:`，要問使用者才能決定的放 `product:`；兩邊都有的拆成兩張，互相連結。
  - 表格以外的 label（GitHub 預設的 `enhancement`、`question`、`wontfix`…，和舊的 `product`）不要貼：決定不做的用 close as not planned，理由寫在 epic。
  - 要新的 label 時，先確認現有的真的放不下；新增時說明（description）寫判斷用的問句或範圍，跟 docs/classification.md 用同一個名字。
  - 做到一半發現放錯段，直接改 label，sub-issue 也搬到對的 epic 底下。

## 下一步

issue 開好之後，執行 `branch` skill 開分支，分支名稱用這張 issue 的編號。修改者回報 issue 要猜才能改時，回到這個 skill 問使用者、改 issue。
