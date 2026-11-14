# AGENTS.md

專案介紹、怎麼跑，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 工作流程

規則只有一份，放在 `.agents/rules/`。照下表的順序做，每一步動手前先讀那一列的規則；其他工具要自己讀，Claude Code 經由 [CLAUDE.md](CLAUDE.md) 讀到這份檔案時，會把表格裡 `@` 開頭的路徑整份載入。

| 步驟 | 做什麼 | 先讀 |
|---|---|---|
| 1 | 開 issue：分支名稱要用到 issue 編號；之後改 issue 也照這份 | [@.agents/rules/issue.md](.agents/rules/issue.md) |
| 2 | 開分支 | [@.agents/rules/branch.md](.agents/rules/branch.md) |
| 3 | 改程式碼、寫註解；改到基礎設施另見下面「改基礎設施」 | [@.agents/rules/comments.md](.agents/rules/comments.md) |
| 4 | commit | [@.agents/rules/commit.md](.agents/rules/commit.md) |
| 5 | 要開 PR 時，先審改到的中文：見 `pr.md` 以「文案審查：」起頭的那一點 | [@.agents/rules/pr.md](.agents/rules/pr.md) |
| 6 | 照改到的地方跑測試、檢查 | [@.agents/rules/commands.md](.agents/rules/commands.md) |
| 7 | 審查、開或改 PR | [@.agents/rules/pr.md](.agents/rules/pr.md) |
| 每一步 | 寫文件、註解、介面文字、prompt、issue、PR、commit 的文字 | [@.agents/rules/writing.md](.agents/rules/writing.md) |

新增規則檔時在這張表加一行，寫成 `[@.agents/rules/<檔名>](.agents/rules/<檔名>)`：`@` 讓 Claude Code 載入，連結讓 GitHub 上點得到。`@` 緊接在「：」這類字後面，Claude Code 不會載入。

`pr.md` 裡的 `/code-review`、`/simplify`、`/security-review` 是 Claude Code 的指令；其他工具用自己的審查功能，審同樣的範圍。

## 改基礎設施

改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
圖上的元件、依賴、參數值還對不對。要改就跟程式碼放在同一個 PR，並把頁尾的「最後核對」日期改成當天。

檔案位置（`F`、`L` 兩張表）記錨點，發佈時才換成行號，檔案多一行、少一行都不用改那頁。
錨點對不上時 `ci/checks.sh` 會紅燈並列出是哪一個，照 [lines.mjs](docs/architecture/lines.mjs) 開頭的寫法改；
要看換成行號的樣子，跑 `node docs/architecture/lines.mjs --out <資料夾>`，開那個資料夾裡的 `index.html`。
