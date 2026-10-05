# web

eat 的前端：Vue 3 + TypeScript + Vite。只是後端 API 的另一個呼叫端（跟 k6、curl 地位一樣），後端不知道它存在。

兩個分頁，各對應後端一個模組：

| 分頁 | 元件 | 打的 API | 契約 |
|---|---|---|---|
| 聊天 | `src/ChatView.vue` | `POST /api/chat` | `src/chat.ts` ↔ `ChatController` |
| 行事曆 | `src/CalendarView.vue` | `POST /api/calendar/parse`（預覽）、`POST /api/calendar/events`（確認）、`GET /api/calendar/events`（月曆一頁） | `src/calendar.ts` ↔ `CalendarController` |

`src/App.vue` 只管切換分頁；月曆格子的日期運算在 `src/monthGrid.ts`（純函式，不碰畫面）。

```
npm install
npm run dev      # http://localhost:5173，/api 轉給 bootRun 的 8090
npm run build    # 先 vue-tsc 型別檢查，再輸出 dist/
```

部署：repo 根目錄 `./gradlew deployWeb`，開 http://localhost:18080。
映像檔是 `Dockerfile` 兩段式（Node build → nginx），nginx 的設定在 `nginx.conf`。

## 為什麼這樣選

- **放在 repo 裡、跟 `src/` 平行，不塞進 `src/main/resources/static`**：前後端的建置工具、生命週期都不同。
  塞進去 Gradle 就得負責跑 npm，改一行 CSS 也要重 build jar。放同一個 repo 是因為
  API 的契約（`src/chat.ts`、`src/calendar.ts` ↔ 兩個 controller 的 record）改的時候要同一個 commit 一起改。
- **用 proxy 不用 CORS**：開發時 Vite 轉 `/api`，部署時 nginx 轉，瀏覽器永遠只看到一個來源。
- **TypeScript 釘在 5.9**：TS 7 是 Go 重寫的版本，沒有舊的 JS API，vue-tsc 3 還接不上
  （會噴 `Package subpath './lib/tsc' is not defined`）。等 vue-tsc 支援再升。
- **非串流**：`/api/chat` 一次回完整回覆；要逐字顯示得先改後端的 port / adapter。
- **分頁不用 vue-router**：只有兩個畫面、不需要網址；加 router 要多一個依賴，nginx 還得加 `try_files`
  （不然在 `/calendar` 按重新整理會 404）。`<KeepAlive>` 讓切分頁不丟聊天內容，行事曆也是第一次點進去才載入。
- **月曆手刻、不用 FullCalendar**：一個 7×6 的 CSS grid 就夠，每一行都看得懂；套件帶來的是週檢視、拖曳這些現在用不到的東西。
- **時間一律當字串、不轉 `Date`**：後端存的是不帶時區的台北牆上時間，`Date` 會被瀏覽器套上時區。
  需要算日期時只用本地時間的建構子和 getter，**不用 `toISOString()`**（它先換成 UTC，台灣早上八點前會變成前一天）。
