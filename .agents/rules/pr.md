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
- 文案審查：要開 PR、執行測試之前，先讓文案審查員審這個分支改到的中文。寫的人重看自己的句子，會照寫的時候的意思去讀，讀不出哪裡不通順。
  - Claude Code 用 `copy-reviewer` subagent（[.claude/agents/copy-reviewer.md](../../.claude/agents/copy-reviewer.md)）。subagent 開始時不帶主 session 的對話；呼叫時只給分支名稱，不附這次改了什麼、為什麼這樣寫。
  - 其他工具開新的對話，貼 `copy-reviewer.md` 的內文。
  - 審查員只回報，不改檔案。列出的句子在同一個分支改；不改的，在 PR 裡寫為什麼。改完又動了文字就重審。
  - 結果寫成 PR 描述「證據」的一點：審到哪個 commit、列了幾句、改了幾句、不改的理由。
  - 沒有改到中文的分支不用審。
- 開 PR 之前先做一輪獨立審查，審這個分支相對 `master` 的 diff：
  - 審多深看改到什麼，一個 PR 跨好幾級就照最高的那級：

    | 級 | 改到什麼 | 執行什麼 |
    |---|---|---|
    | 1 | 只有文件、規則、模板 | `/code-review medium` |
    | 2 | 程式碼（含腳本、workflow） | `/simplify` 再 `/code-review high` |
    | 3 | `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`backend/src/main/resources/db/migration/`，或後端的 `auth/` 模組、各模組的 `adapter/in/web/`（對外 API） | 第 2 級，再加 `/security-review`；出錯難收拾的（資料遷移、刪資源）由人手動執行 `/code-review ultra` |

    - `/code-review` 找 bug，文件寫錯路徑、說錯行為也算；等級要寫明，不寫會沿用上次輸入的等級。
    - `/simplify` 找重複、可以簡化的地方，會直接改，所以排在 `/code-review` 前面，`/code-review` 才審得到最後進 PR 的那一版。
    - `/code-review ultra` 是雲端多 agent 審查、另外計費，只能由人啟動；不自己另外組多個 agent 投票，同一個模型的錯誤會一起錯，多幾票不會比較準。
  - 找到的問題在同一個分支修掉；不修的，在 PR 裡寫為什麼。
  - 結果寫成 PR 描述「證據」的一點：屬於第幾級、執行了哪個、審到哪個 commit、找到幾個、怎麼處理。之後 push 又改了程式碼就重新執行，更新這一點。
  - 理由：寫的人重看自己的 diff，會沿用寫的時候的假設；先讓審查指令過一輪，人審的時候看得到已經抓過什麼、剩下什麼要自己判斷。
    分級是因為出錯的代價不同：改一行文件跟改部署流程審得一樣深，前者浪費、後者不夠。
