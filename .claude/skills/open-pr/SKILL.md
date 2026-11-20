---
name: open-pr
description: 分支開好、要開始改到開 PR 時使用（由主 session 執行，在 `branch` 之後）：照順序呼叫修改者、審核者、三個投票者，兩票通過後執行測試、開 PR 或改 PR。內容包括 agent 的 prompt 格式、輪數上限、審查分級表、要執行的測試、PR 描述的寫法。
disable-model-invocation: true
---

# open-pr

這份寫主 session 怎麼把一張 issue 從分支帶到 PR。修改者、審核者要用的審查分級表在 [review-levels skill](../review-levels/SKILL.md)，由定義檔的 `skills:` 預先載入。

## 開 PR 前：修改、審核、投票

開 PR 前，改動由五個 agent 分工：一個修改、一個審核、三個投票，兩票通過才開 PR。

- 五個 agent 都從新的對話開始，讀不到主 session 跟使用者的對話，也讀不到其他 agent 的推論。寫的人重看自己的 diff，會沿用寫的時候的假設；審的人先拿到寫的人的說明，還沒查就會接受那個結論。
- 定義在 `.claude/agents/`：修改者 [pr-author.md](../../agents/pr-author.md)、審核者 [pr-reviewer.md](../../agents/pr-reviewer.md)，三個投票者 [pr-voter-fable.md](../../agents/pr-voter-fable.md)、[pr-voter-opus.md](../../agents/pr-voter-opus.md)、[pr-voter-sonnet.md](../../agents/pr-voter-sonnet.md)。
- 主 session 只做這些：開 issue、問分支名稱、照下面的順序呼叫五個 agent、轉交回報、執行測試、開 PR。
  - 不改檔案。使用者在對話裡交代的事先照 `issue` skill 寫進 issue，修改者才讀得到。修改者回報 issue 要猜才能改時，也回到 `issue` skill 問使用者、改 issue，再重新呼叫修改者。
  - agent 的回報原樣存成檔案，一份回報一個檔案，放在 repo 外面的資料夾（scratchpad 這類）。轉交給下一個 agent 時給檔案路徑，不摘要、不改寫，也不貼內文。
  - 回報檔的路徑只給下一步要用的 agent：投票者讀到別的票，就不是各自判斷。
  - 在 checkout 這個分支的資料夾裡呼叫 agent。呼叫前 `git status --porcelain` 要沒有輸出：審核者、投票者讀的是工作目錄的檔案，還沒 commit 的改動會被當成分支的內容。
  - 不在呼叫 agent 的資料夾切換分支。Claude Code 從工作目錄讀 `.claude/agents/` 的定義，切換分支後要過幾秒才換成新的版本，馬上呼叫會拿到上一個分支的定義，`model` 也會跟著錯。
  - 分支改到 `CLAUDE.md`、`.claude/rules/`、`.claude/skills/` 或 `.claude/agents/` 時，從新開的 session 呼叫 agent：`CLAUDE.md` 和 `.claude/rules/` 載入的是 session 開始時的版本，agent 會先照那一版做。
  - 分支名稱裡沒有 issue 編號時（已經 push、照 `branch` skill 不改名的分支），呼叫審核者、投票者之前先停下來問使用者：這兩種 agent 只拿到分支名稱，issue 編號取自名稱。
  - 呼叫審核者、投票者之前先執行 `git fetch origin`。agent 自己不 fetch：三個投票者同時 fetch 會搶同一個 ref 的 lock。
  - 回報第一行的 commit 不等於 `git rev-parse <分支>`，或 agent 停下來回報工作目錄不對時，那份回報不算數：把工作目錄處理好，重新呼叫同一種 agent。

### 呼叫 agent

- 每一次都用 `Agent` 開新的 agent，`subagent_type` 填定義檔的 `name`。不用 `SendMessage` 接著問上一輪的 agent：上一輪 agent 的 context 還留著那一輪的推論，等於讀過寫的人的說明。
- 不另外指定模型：呼叫時指定的模型優先於定義檔的 `model`，三個投票者可能變成同一個模型。
- prompt 只照下面的格式填，不加其他說明。`<回報檔>` 一行一個路徑，第一輪沒有就整行拿掉：

  | agent | prompt |
  |---|---|
  | 修改者 `pr-author` | `issue 編號：<編號>`、`分支名稱：<分支>`、`回報檔：<回報檔>` |
  | 審核者 `pr-reviewer` | `分支名稱：<分支>`、`回報檔：<回報檔>` |
  | 投票者 `pr-voter-*` | `分支名稱：<分支>` |

  表格裡的每個欄位各占一行。回報檔放哪些：
  - 修改者第二輪起：觸發這一輪的審核者回報、三張票或測試的輸出。
  - 審核者第二輪起：上一輪審核者的回報、修改者這一輪的回報；修改者這一輪如果是因為投票或測試沒過才開始的，也給三張票或測試的輸出。
- 呼叫前關掉自動記憶：Claude Code 文件寫 subagent 不載入主 session 的自動記憶，但在桌面 app 呼叫的 subagent，context 裡有 `MEMORY.md` 的內容，裡面記著使用者在對話裡說過的偏好和決定。在 `.claude/settings.local.json` 設 `"autoMemoryEnabled": false`（只對本機這個專案有效，不 commit），重新開 session 再執行這個 skill；開完 PR 再改回來。

### 順序

1. 修改者改、commit。
2. 審核者審。有列問題就回到第 1 步；修改者改完，換新的審核者再審。
3. 審核者沒有列問題之後，同時呼叫三個投票者，各投一票。
4. 兩票通過就照下面的「照改到的地方驗證」執行測試、開 PR。沒過就帶著三張票回到第 1 步；測試沒過也回到第 1 步，帶的是測試的輸出。

- 修改和審核最多 3 輪、投票最多 2 次，超過就停下來問使用者。投票沒過回到第 1 步之後，輪數接著算。修改者因為 issue 要猜而停下來、回報因為 commit 或工作目錄不對而不算數，重新呼叫時都不算一輪。
- 審核和投票看的要是分支最後一個 commit。審核者、投票者的回報第一行寫看的 commit；開 PR 前核對最後一輪審核和三張票寫的都等於 `git rev-parse <分支>`。之後又有 commit 就重新審核、投票。
- 三個投票者用不同的模型（`fable`、`opus`、`sonnet`）：同一個模型的錯誤會一起錯，三票用同一個模型的話，多幾票不會比較準。怎麼投票見 [vote skill](../vote/SKILL.md)。

### 審查分級

審多深、修改者和審核者各執行哪些指令，見 [review-levels skill](../review-levels/SKILL.md)。

### 呼叫不到定義時

session 呼叫不到分支上的定義時（例如定義檔是 session 開始之後才加的），每個 agent 各開一個新的對話，貼上定義檔的內文和定義檔 `skills:` 列的 skill 全文；三個投票者各用不同的模型。審核者的文案審查也另外開一個新的對話，貼上 `copy-reviewer.md` 的內文。

## 照改到的地方驗證

兩票通過之後才執行，commit 前、投票前都不用。這些可以直接執行，不用先問；指令都在 repo 根目錄下執行，每種測試要先準備什麼見 [docs/testing.md](../../../docs/testing.md)：

| 改到 | 執行 |
|---|---|
| 後端 Java | `backend/gradlew -p backend test`：單元測試、ArchUnit 分層規則，編譯時也會執行 Error Prone |
| 後端的 package、import | `backend/gradlew -p backend test --tests '*ArchitectureTest'`：只看依賴方向，最快 |
| 後端的資料庫、S3 adapter、整個 app 的組裝 | `backend/gradlew -p backend integrationTest`（要開 Docker） |
| DB migration | `ci/migrations.sh` |
| prompt、模型相關的程式 | `backend/gradlew -p backend eval`（要開模型） |
| 前端 `web/` | `pnpm --dir web run lint`、`pnpm --dir web test`、`pnpm --dir web run build`：`build` 會執行 `vue-tsc`，型別錯誤只有 `vue-tsc` 檢查得出來 |
| 前端畫面 | `pnpm --dir web run e2e` |
| `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`docs/architecture/` | `node docs/architecture/lines.mjs --check` |
| `ops/mimir/rules/` | `ci/rules.sh` |
| 跨好幾塊、準備 push | `ci/run.sh`（要開 Docker）：跟 CI 同一套檢查 |
| 演練還原資料庫，不碰線上資料庫 | `just restore-verify` |

前端指令要先裝好依賴：`corepack enable`、`pnpm --dir web install`。前端用 `pnpm`，不用 `npx`，理由見 [web/README.md](../../../web/README.md#測試)。`git push` 要先問使用者，見 `commands.md`。

## PR 描述

- PR 標題跟 commit 標題同一種寫法（見 [commit skill](../commit/SKILL.md)）。issue 和 PR 各寫什麼見 [issue skill](../issue/SKILL.md) 的「寫法」。
- PR 描述照 `.github/pull_request_template.md`，標題和內文都用中文，每段用條列，不適用的段落整段拿掉。「證據」的改之前／改之後只放量得到的東西（數字、截圖、指令輸出）；沒有的話只寫改之後，問題的描述留在 issue。演練時間軸這類很長的紀錄收進 `<details>`。
  - 「摘要」寫改了什麼、改完誰會感覺到差別（使用者、開發者，或都不會）；讀完 PR 標題、摘要和連過去的 issue，就要知道哪裡有問題、改完會怎樣。用字照 `writing.md`。
- 「證據」附上每一輪審核的原始回報、修改者第二輪起的回報（不改的理由寫在裡面）、每一次投票的三張票。一份回報收進一個 `<details>`，`<summary>` 寫哪一輪、哪個 agent、看的 commit；看 PR 的人才能對照哪些改了、哪些沒改。
  - 投票者判斷成「分支上看不出來」的驗收條件，在「證據」逐條附上。
- PR 描述的截圖直接傳進 PR，不 commit 進分支：圖只有審查當下有用，一進 master 就永遠留在歷史裡，每次 clone 都要下載。
  - 用 `gh pr create` 或 `gh pr edit` 的 `--attach <檔案>`（gh 2.99.0 起才有）。描述裡先寫 `![說明](<本機路徑>)`，
    gh 上傳後會換成 GitHub 的網址；網頁上直接拖進描述也一樣。圖片最多 10 MB，影片（MP4、MOV、WebM）也能傳。
  - 傳完看一次描述：本機路徑都換成 `https://github.com/user-attachments/...` 才算成功，沒換到的會被加在描述最後，原處留下壞圖。
  - `gh --version` 不到 2.99.0 就停下來請使用者升級，不要退回把圖 commit 進分支。
  - 截圖先存在 scratchpad 這類 repo 外面的資料夾，不要放進 worktree，免得順手 commit 進去。
  - 文件（README、`docs/`）要引用的圖才進 master，放 `docs/images/`。
  - 演練紀錄（runbook、腳本會連過去的 .md）算文件，放 `docs/drills/`，它引用的圖一樣放 `docs/images/`。
- PR 描述不要有 `Generated with Claude Code`。
- PR 不貼 label：分類（`stage:`／`product:`、`p0`／`p1`、`area:`）都看它 `Closes` 的 issue，只維護一份。
  做的過程發現 issue 放錯段或少了 `area:`，回去改 issue 的 label。

## 下一步

PR 開好就是這張 issue 工作流程的最後一步。PR 合併前又有要改的，從這個 skill 的第 1 步（呼叫修改者）重新開始；需求變了，先回到 `issue` skill 改 issue。
