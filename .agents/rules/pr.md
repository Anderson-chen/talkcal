# PR 規則

- PR 標題跟 commit 標題同一種寫法（見 `commit.md`）。
- issue 和 PR 各寫各的，不重複：

  | | issue | PR |
  |---|---|---|
  | 什麼時候寫 | 動手前 | 改完 |
  | 寫什麼 | 為什麼要做、打算怎麼做、做完怎麼算過關（驗收條件） | 實際改了什麼、執行過的證據 |
  | 不寫 | 做完的結果（測試輸出、截圖） | 問題的背景、目標：用 `Closes #N` 連到 issue |

  做到一半發現做法跟 issue 寫的不同，PR 寫實際的做法和為什麼改，issue 的「做法」不回頭改。
- PR 描述照 `.github/pull_request_template.md`，標題和內文都用中文，每段用條列，不適用的段落整段拿掉。「證據」的改之前／改之後只放量得到的東西（數字、截圖、指令輸出）；沒有的話只寫改之後，問題的描述留在 issue。演練時間軸這類很長的紀錄收進 `<details>`。
  - 「摘要」寫改了什麼、改完誰會感覺到差別（使用者、開發者，或都不會）；讀完 PR 標題、摘要和連過去的 issue，就要知道哪裡有問題、改完會怎樣。用字照 `writing.md`。
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

## 開 PR 前：修改、審核、投票

開 PR 前，改動由五個 agent 分工：一個修改、一個審核、三個投票，兩票通過才開 PR。

- 五個 agent 都從新的對話開始，讀不到協調的 session 跟使用者的對話，也讀不到其他 agent 的推論。寫的人重看自己的 diff，會沿用寫的時候的假設；審的人先拿到寫的人的說明，還沒查就會接受那個結論。
- 定義在 `.claude/agents/`：修改者 [pr-author.md](../../.claude/agents/pr-author.md)、審核者 [pr-reviewer.md](../../.claude/agents/pr-reviewer.md)，三個投票者 [pr-voter-fable.md](../../.claude/agents/pr-voter-fable.md)、[pr-voter-opus.md](../../.claude/agents/pr-voter-opus.md)、[pr-voter-sonnet.md](../../.claude/agents/pr-voter-sonnet.md)。呼叫時給什麼，寫在各自的 `description`。
- 協調的 session 是跟使用者對話的那一個，只做這些：開 issue、問分支名稱、依序呼叫五個 agent、轉交回報、執行測試、開 PR。
  - 不改檔案。使用者在對話裡交代的事先寫進 issue，修改者才讀得到。
  - 呼叫 agent 時只給 `description` 列的東西，不附自己的說明，也不另外指定模型：呼叫時指定的模型優先於定義檔的 `model`。
  - agent 的回報原樣存成檔案，一份回報一個檔案，放在 repo 外面的資料夾（scratchpad 這類）。轉交給下一個 agent 時給檔案路徑，不摘要、不改寫。
  - 在 checkout 這個分支的資料夾裡呼叫 agent，而且沒有還沒 commit 的改動：審核者、投票者讀的是工作目錄的檔案。
- 順序：
  1. 修改者改、commit。
  2. 審核者審。有列問題就回到第 1 步；修改者改完，換新的審核者再審。
  3. 審核者沒有列問題之後，同時呼叫三個投票者，各投一票。
  4. 兩票通過就照 `commands.md` 執行測試、開 PR。沒過就帶著三張票回到第 1 步；測試沒過也回到第 1 步，帶的是測試的輸出。
- 修改和審核最多 3 輪、投票最多 2 次，超過就停下來問使用者。投票沒過回到第 1 步之後，輪數接著算。
- 審核和投票看的要是分支最後一個 commit。審核者、投票者的回報第一行寫看的 commit；開 PR 前核對最後一輪審核和三張票寫的都等於 `git rev-parse <分支>`。之後又有 commit 就重新審核、投票。
- 三個投票者用不同的模型（`fable`、`opus`、`sonnet`）：同一個模型的錯誤會一起錯，三票用同一個模型的話，多幾票不會比較準。怎麼投票見 `vote.md`。
- 審多深看改到什麼，一個 PR 跨好幾級就照最高的那級。出錯的代價不同：改一行文件跟改部署流程審得一樣深，前者浪費、後者不夠。

  | 級 | 改到什麼 | 修改者在第一輪回報前執行 | 審核者執行 |
  |---|---|---|---|
  | 1 | 只有文件、規則、模板 | 不用 | `/code-review medium <分支>` |
  | 2 | 程式碼（含腳本、workflow） | `/simplify <分支>` | `/code-review high <分支>` |
  | 3 | `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`backend/src/main/resources/db/migration/`，或後端的 `auth/` 模組、各模組的 `adapter/in/web/`（對外 API） | `/simplify <分支>` | `/code-review high <分支>` 和 `/security-review` |

  - `/code-review` 找 bug，文件寫錯路徑、說錯行為也算；等級要寫明，不寫會沿用上次輸入的等級。
  - `/simplify` 找重複、可以簡化的地方，會直接改檔案，所以由修改者執行，審核者才審得到最後進 PR 的那一版。
    - 先 commit 再執行，改到的檔案另外 commit：`/simplify` 讀的是已經 commit 的 diff。
    - 分支名稱要給：還沒 push 的分支沒有 upstream，不給的話 `/simplify` 只讀最後一個 commit 的 diff。
  - `/security-review` 不接受分支參數，審的是工作目錄 checkout 的分支。
  - 改到中文的分支，審核者另外做文案審查：呼叫 `copy-reviewer`（[copy-reviewer.md](../../.claude/agents/copy-reviewer.md)），只給分支名稱。寫的人重看自己的句子，會照寫的時候的意思去讀，讀不出哪裡不通順。
  - 第 3 級裡出錯難收拾的（資料遷移、刪資源），由人手動執行 `/code-review ultra`：這是雲端多 agent 審查、另外計費，只能由人啟動。
- PR 描述的「證據」附上每一輪審核的原始回報、修改者第二輪起的回報（不改的理由寫在裡面）、每一次投票的三張票。一份回報收進一個 `<details>`，`<summary>` 寫哪一輪、哪個 agent、看的 commit；看 PR 的人才能對照哪些改了、哪些沒改。
  - 投票者判斷成「分支上看不出來」的驗收條件，在「證據」逐條附上。
- 其他工具沒有 subagent，或 session 呼叫不到分支上的定義時（例如定義檔是 session 開始之後才加的），每個 agent 各開一個新的對話，貼上定義檔的內文；三個投票者各用不同的模型。
