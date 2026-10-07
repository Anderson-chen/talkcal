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
npm install
npm run dev      # http://localhost:5173，/api 轉給 bootRun 的 8090（設 EAT_API 可以改轉到別台）
npm run build    # 先 vue-tsc 型別檢查，再輸出 dist/
```

部署：repo 根目錄 `./gradlew deployWeb`，開 http://localhost:18080。
映像檔是 `Dockerfile` 兩段式（Node build → nginx），nginx 的設定在 `nginx.conf`。

## 為什麼這樣選

- **放在 repo 裡、跟 `src/` 平行，不塞進 `src/main/resources/static`**：前後端的建置工具、生命週期都不同。
  塞進去 Gradle 就得負責跑 npm，改一行 CSS 也要重 build jar。放同一個 repo 是因為
  API 的契約（`src/calendar.ts` ↔ 兩個 controller 的 record）改的時候要同一個 commit 一起改。
- **用 proxy 不用 CORS**：開發時 Vite 轉 `/api`，部署時 nginx 轉，瀏覽器永遠只看到一個來源。
- **TypeScript 釘在 5.9**：TS 7 是 Go 重寫的版本，沒有舊的 JS API，vue-tsc 3 還接不上
  （會噴 `Package subpath './lib/tsc' is not defined`）。等 vue-tsc 支援再升。
- **非串流**：AI 助理一次回完整回覆；要逐字顯示得先改後端的 adapter。
- **不用 vue-router**：畫面少、不需要網址；加 router 要多一個依賴，nginx 還得加 `try_files`
  （不然在 `/calendar` 按重新整理會 404）。
- **月曆手刻、不用 FullCalendar**：一個 7×6 的 CSS grid 就夠，每一行都看得懂；套件帶來的是週檢視、拖曳這些現在用不到的東西。
- **時間一律當字串、不轉 `Date`**：後端存的是不帶時區的台北牆上時間，`Date` 會被瀏覽器套上時區。
  需要算日期時只用本地時間的建構子和 getter，**不用 `toISOString()`**（它先換成 UTC，台灣早上八點前會變成前一天）。
