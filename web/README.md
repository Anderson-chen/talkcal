# web

talkcal 的前端：Vue 3 + TypeScript + Vite。只是後端 API 的另一個呼叫端（跟 k6、curl 地位一樣），後端不知道它存在。

畫面只有行事曆和它的 AI 助理，對應後端的 calendar 模組：

| 畫面 | 元件 | 打的 API | 契約 |
|---|---|---|---|
| 行事曆 | `src/calendar/CalendarView.vue` | `POST /api/calendar/assistant`（AI 助理對話）、`POST /api/calendar/events`（確認、手動新增）、`GET /api/calendar/events`（一頁）、`DELETE /api/calendar/events/{id}` | `src/calendar.ts` ↔ `CalendarController`、`CalendarAssistantController` |

`src/App.vue` 只放行事曆；月曆格子的日期運算在 `src/monthGrid.ts`（純函式，不碰畫面）。

行事曆照設計稿（Design 畫布「行事曆 Prototype」）做。設計稿原檔、以及實作跟它哪裡不一樣，記在
[`docs/design/calendar/`](../docs/design/calendar/README.md)。`src/calendar/` 底下：

- `useCalendar.ts`、`useAssistant.ts`、`useTheme.ts`：狀態與動作，`CalendarView` 建一份 provide 下去。
  桌面版和手機版共用，視窗拉窄換版面時，選的日期、AI 對話都還在
- `DesktopCalendar.vue`（側欄 + 月/週/日 + AI 助理側欄）、`MobileCalendar.vue`（760px 以下）
- 共用零件：`MonthGrid`、`TimeGrid`（週、日時間軸）、`MiniMonth`、`AssistantChat`、`EventDetail`、`EventForm`、`ThemePicker`
- `timeline.ts`（跨夜行程切段、重疊並排）、`theme.ts`（4 種主題、分類顏色）：純資料與純函式

```
corepack enable   # 只要做一次：之後打 pnpm，corepack 會照 package.json 的 packageManager 用那一版
pnpm install
pnpm dev          # http://localhost:5173，/api 轉給 bootRun 的 8090（設 EAT_API 可以改轉到別台）
pnpm build        # 先 vue-tsc 型別檢查，再輸出 dist/
pnpm lint         # ESLint：沒處理的 Promise、v-for 沒 key、E2E 忘了 await…（選型理由見 ci/README.md 的「lint」）
```

## 測試

都在 `web/` 裡跑，**用 `pnpm`（`pnpm test`、`pnpm exec playwright …`），不要用 `npx`**：
在別的資料夾打 `npx vitest`，npx 找不到這裡裝的版本，會悄悄從網路下載另一版來跑（實際踩過兩次）。
`pnpm exec` 只跑這個資料夾裝好的版本，找不到就報錯；真的要臨時下載來跑，得明講 `pnpm dlx`。

```
pnpm test            # Vitest：純函式（src/**/*.test.ts），不到 1 秒
pnpm test:watch      # 同上，存檔就重跑
pnpm e2e             # Playwright：真的 Chromium，桌面、手機兩種版面（e2e/）
pnpm e2e:ui          # 同上，開視窗一步一步看每個動作前後的畫面、網路請求
```

| | Vitest | Playwright |
|---|---|---|
| 測什麼 | 純函式：規則、運算 | 元件和整頁：接線、畫面、桌面和手機版 |
| 檔名 | `src/**/*.test.ts` | `e2e/*.desktop.spec.ts`、`e2e/*.mobile.spec.ts` |
| 瀏覽器 | 不需要 | 真的 Chromium（第一次要 `pnpm exec playwright install chromium`） |
| API | 不碰 | `e2e/fakeApi.ts` 用 `page.route` 攔下來回假資料，不需要後端 |

- **沒有模擬 DOM 的元件測試**：元件裡的規則先抽成純函式（例如 `src/calendar/eventDraft.ts`）用 Vitest 測透，元件只剩畫面和接線，交給 Playwright 在真的瀏覽器裡看。
- **找元素用使用者看得到的東西**：role、label、按鈕上的字，不用 CSS class。名字撞到時用範圍限定（在某一塊裡找），不用 `.first()`、`.last()` —— 那是靠順序，版面一改就點錯而且不會報錯。
- **寫 E2E 之前先打開頁面看**：只看原始碼會猜錯畫面上實際有什麼（手機版有兩個同名的「新增行程」）。
- **時間固定在 2026-10-06 11:30**（`page.clock`）：行事曆到處用到「今天」。
- 版本都釘死（`pnpm add --save-exact`），挑發布超過兩週的版本。CI 映像檔裡的 Chromium 版本從 `package.json` 讀（`ci/Dockerfile`）。

部署：在 `backend/` 裡 `./gradlew deployWeb`，開 http://localhost:18080。
映像檔是 `Dockerfile` 兩段式（Node build → nginx），nginx 的設定在 `nginx.conf`。

## 為什麼這樣選

- **放在 repo 裡、跟 `backend/` 平行，不塞進 `backend/src/main/resources/static`**：前後端的建置工具、生命週期都不同。
  塞進去 Gradle 就得負責跑 pnpm，改一行 CSS 也要重 build jar。放同一個 repo 是因為
  API 的契約（`src/calendar.ts` ↔ 兩個 controller 的 record）改的時候要同一個 commit 一起改。
- **用 pnpm 不用 npm**（#107）：npm 的 `node_modules` 是每個資料夾各一份實體檔案，平行開好幾個 worktree 就有好幾份。
  pnpm 把套件只存一份在全域的 store，各資料夾的 `node_modules` 是指向它的硬連結。另一個好處是比較嚴格：
  沒寫在 `package.json` 的套件 import 不到，不會因為「剛好被別的套件帶進來」而能用，哪天那個套件不帶了才壞。
  - 版本釘在 `package.json` 的 `packageManager`（含 sha512），由 Node 附的 corepack 下載、驗證，本機、CI 映像檔、
    `Dockerfile` 用的都是同一版。用 11 不用最新的 12：pnpm 12 改發原生執行檔，Node 22.14 附的 corepack 0.31 跑不起來
  - pnpm 11 只讀 `pnpm_config_` 開頭的環境變數（例如 `pnpm_config_store_dir`），`npm_config_` 開頭的它不看
  - 量測（2026-10-19，Windows 11，在專案同層開全新 worktree，從 `git worktree add` 完到 `test` 跑完；
    「冷」是空的 npm cache／pnpm store，「熱」是已經裝過一次）：

    | | 時間（冷） | 時間（熱） | `node_modules` | 這個 worktree 新占的磁碟 |
    |---|---|---|---|---|
    | npm（`npm ci`） | 5.5 秒 | 3.6–4.0 秒 | 95 MB，1731 個檔案 | 95 MB |
    | pnpm（`pnpm install --frozen-lockfile`） | 6.8 秒 | 3.4 秒 | 95 MB，1785 個檔案 | 不到 1 MB（其餘是指向 store 的硬連結） |

    時間差不多：這裡只有 68 個套件，裝起來本來就快；冷的時候 pnpm 還要多建 store，反而慢一點。
    省下來的是磁碟：每多開一個 worktree，npm 多 95 MB，pnpm 幾乎不多占。
    「新占的磁碟」是 `find node_modules -type f -links 1` 加總：硬連結數大於 1 的檔案跟 store 共用同一份資料
  - 反例（嚴格有沒有真的擋）：在 `src/` 加一支 `import { capitalize } from '@vue/shared'`（vue 的依賴，沒寫在 `package.json`）。
    npm：`vitest`、`vue-tsc` 都過；pnpm：`vitest` 報 `Cannot find package '@vue/shared'`、`vue-tsc` 報 `TS2307`，兩個都失敗
- **用 proxy 不用 CORS**：開發時 Vite 轉 `/api`，部署時 nginx 轉，瀏覽器永遠只看到一個來源。
- **TypeScript 釘在 5.9**：TS 7 是 Go 重寫的版本，沒有舊的 JS API，vue-tsc 3 還接不上
  （會噴 `Package subpath './lib/tsc' is not defined`）。等 vue-tsc 支援再升。
- **非串流**：AI 助理一次回完整回覆；要逐字顯示得先改後端的 adapter。
- **不用 vue-router**：畫面少、不需要網址；加 router 要多一個依賴，nginx 還得加 `try_files`
  （不然在 `/calendar` 按重新整理會 404）。
- **月曆手刻、不用 FullCalendar**：一個 7×6 的 CSS grid 就夠，每一行都看得懂；套件帶來的是週檢視、拖曳這些現在用不到的東西。
- **時間一律當字串、不轉 `Date`**：後端存的是不帶時區的台北牆上時間，`Date` 會被瀏覽器套上時區。
  需要算日期時只用本地時間的建構子和 getter，**不用 `toISOString()`**（它先換成 UTC，台灣早上八點前會變成前一天）。
