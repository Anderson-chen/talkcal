# AGENTS.md

在這個 repo 工作的 AI 工具（Codex、Cursor、Copilot、Gemini CLI、Claude Code…）都照這份。
專案是什麼、怎麼跑，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 指令

都在 repo 根目錄下打。前端指令要先裝好依賴（`corepack enable`、`pnpm --dir web install`，見 [web/README.md](web/README.md)）：

| 要做什麼 | 指令 |
|---|---|
| 跟 CI 一模一樣的整輪檢查（要開 Docker） | `ci/run.sh` |
| 後端單元測試、架構規則 | `cd backend && ./gradlew test` |
| 後端整合測試（真的 PostgreSQL、模型） | `cd backend && ./gradlew integrationTest` |
| 前端單元測試 | `pnpm --dir web test` |
| 前端 E2E | `pnpm --dir web run e2e` |
| 前端 lint | `pnpm --dir web run lint` |
| 後端 lint（Error Prone，編譯時檢查） | `cd backend && ./gradlew classes testClasses` |

## 動手前要讀的規則

規則只有一份，放在 `.claude/rules/`。Claude Code 會自動載入；其他工具要在做這些事之前自己讀：

| 要做什麼 | 先讀 |
|---|---|
| commit | [.claude/rules/commit.md](.claude/rules/commit.md) |
| 開或改 PR | [.claude/rules/pr.md](.claude/rules/pr.md) |
| 開或改 issue | [.claude/rules/issue.md](.claude/rules/issue.md) |

`pr.md` 裡的 `/code-review`、`/simplify`、`/security-review` 是 Claude Code 的指令；其他工具用自己的審查功能，審同樣的範圍。

## 改基礎設施

改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
並把頁尾的「最後核對」日期改成當天。
