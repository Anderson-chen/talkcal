# AGENTS.md

專案介紹、怎麼跑，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 指令

改完程式碼用這張表驗證，都在 repo 根目錄下打；每種測試要先準備什麼，見 [docs/testing.md](docs/testing.md)。
前端指令要先裝好依賴（`corepack enable`、`pnpm --dir web install`，見 [web/README.md](web/README.md)）：

| 要做什麼 | 指令 |
|---|---|
| 跟 CI 一模一樣的整輪檢查（要開 Docker） | `ci/run.sh` |
| 後端單元測試、架構規則 | `cd backend && ./gradlew test` |
| 後端整合測試（要開 Docker） | `cd backend && ./gradlew integrationTest` |
| 前端單元測試 | `pnpm --dir web test` |
| 前端 E2E | `pnpm --dir web run e2e` |
| 前端 lint | `pnpm --dir web run lint` |
| 後端 lint（Error Prone，編譯時檢查） | `cd backend && ./gradlew classes testClasses` |
| 架構圖的錨點都對得上檔案 | `node docs/architecture/lines.mjs --check` |

## 動手前要讀的規則

規則只有一份，放在 `.agents/rules/`。其他工具要在做這些事之前自己讀；Claude Code 經由 [CLAUDE.md](CLAUDE.md) 讀到這份檔案時，會把表格裡 `@` 開頭的路徑整份載入。

| 要做什麼 | 先讀 |
|---|---|
| 換到別的分支做事、發現 commit 落錯分支 | [@.agents/rules/branch.md](.agents/rules/branch.md) |
| commit | [@.agents/rules/commit.md](.agents/rules/commit.md) |
| 開或改 PR | [@.agents/rules/pr.md](.agents/rules/pr.md) |
| 開或改 issue | [@.agents/rules/issue.md](.agents/rules/issue.md) |
| 寫或改文件、程式碼註解、issue、PR、commit 的文字 | [@.agents/rules/writing.md](.agents/rules/writing.md) |
| 寫或改程式碼註解 | [@.agents/rules/comments.md](.agents/rules/comments.md) |

新增規則檔時在這張表加一行，寫成 `[@.agents/rules/<檔名>](.agents/rules/<檔名>)`：`@` 讓 Claude Code 載入，連結讓 GitHub 上點得到。`@` 緊接在「：」這類字後面，Claude Code 不會載入。

`pr.md` 裡的 `/code-review`、`/simplify`、`/security-review` 是 Claude Code 的指令；其他工具用自己的審查功能，審同樣的範圍。

## 改基礎設施

改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
圖上的元件、依賴、參數值還對不對。要改就跟程式碼放在同一個 PR，並把頁尾的「最後核對」日期改成當天。

檔案位置（`F`、`L` 兩張表）記錨點，發佈時才換成行號，檔案多一行、少一行都不用改那頁。
錨點對不上時 `ci/checks.sh` 會紅燈並列出是哪一個，照 [lines.mjs](docs/architecture/lines.mjs) 開頭的寫法改；
要看換成行號的樣子，跑 `node docs/architecture/lines.mjs --out <資料夾>`，開那個資料夾裡的 `index.html`。
