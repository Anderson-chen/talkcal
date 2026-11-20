# CLAUDE.md

專案介紹、使用方式，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 工作流程

一張 issue 照下表的順序做，每一步執行那一列的 skill。skill 放在 `.claude/skills/`，做到那一步才載入全文；`.claude/rules/` 放每一步都要遵守的規則，Claude Code 在 session 開始時整個資料夾載入。主 session 指使用者正在對話的那個 session。

| 步驟 | 誰執行 | 做什麼 | 執行的 skill |
|---|---|---|---|
| 1 | 主 session | 開 issue、改 issue：先進 plan mode 問清楚，分支名稱要用到 issue 編號 | `issue` |
| 2 | 主 session | 開分支；分支已經建好（例如桌面 app 開 session 時建的）也要看名稱合不合規則 | `branch` |
| 3 | 主 session | 依序呼叫下面的 3-1 到 3-3，兩票通過後執行測試、開或改 PR | `open-pr` |
| 3-1 | 修改者（`pr-author`） | 修改、commit：改程式碼、寫註解照 `comments.md`，改到基礎設施另見下面「改基礎設施」 | `commit`、`open-pr`（預先載入） |
| 3-2 | 審核者（`pr-reviewer`） | 審核：列出問題；有列就回到 3-1 | `open-pr`（預先載入） |
| 3-3 | 三個投票者（`pr-voter-fable`、`pr-voter-opus`、`pr-voter-sonnet`） | 投票：各投一票；兩票通過才執行測試、開 PR，沒過就回到 3-1 | `vote`（預先載入） |

括號裡是 `.claude/agents/` 的 subagent 名稱；「預先載入」指定義檔的 `skills:`。每一步寫文件、註解、介面文字、prompt、issue、PR、commit 的文字，都照 `.claude/rules/writing.md`；要執行指令時照 `.claude/rules/commands.md`。

新增一步時，skill 放進 `.claude/skills/<名稱>/SKILL.md`，最後一段寫下一步執行哪個 skill，並在這張表加一行。每一步都要遵守的規則才放進 `.claude/rules/`。

## 改基礎設施

改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
圖上的元件、依賴、參數值還對不對。要改就跟程式碼放在同一個 PR，並把頁尾的「最後核對」日期改成當天。

檔案位置（`F`、`L` 兩張表）記錨點，發佈時才換成行號，檔案多一行、少一行都不用改那頁。
錨點對不上時 `ci/checks.sh` 會紅燈並列出是哪一個，照 [lines.mjs](docs/architecture/lines.mjs) 開頭的寫法改；
要看換成行號的樣子，執行 `node docs/architecture/lines.mjs --out <資料夾>`，開那個資料夾裡的 `index.html`。
