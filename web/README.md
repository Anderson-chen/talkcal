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
npm run lint     # ESLint：沒處理的 Promise、v-for 沒 key、E2E 忘了 await…（選型理由見 ci/README.md 的「lint」）
```

## 測試

都在 `web/` 裡跑，**用 `npm run`、不要用 `npx`**：在別的資料夾打 `npx vitest`，npx 找不到這裡裝的版本，會悄悄從網路下載另一版來跑（實際踩過兩次）。

```
npm test            # Vitest：純函式（src/**/*.test.ts），不到 1 秒
npm run test:watch  # 同上，存檔就重跑
npm run e2e         # Playwright：真的 Chromium，桌面、手機兩種版面（e2e/）
npm run e2e:ui      # 同上，開視窗一步一步看每個動作前後的畫面、網路請求
```

| | Vitest | Playwright |
|---|---|---|
| 測什麼 | 純函式：規則、運算 | 元件和整頁：接線、畫面、桌面和手機版 |
| 檔名 | `src/**/*.test.ts` | `e2e/*.desktop.spec.ts`、`e2e/*.mobile.spec.ts` |
| 瀏覽器 | 不需要 | 真的 Chromium（第一次要 `npx playwright install chromium`） |
| API | 不碰 | `e2e/fakeApi.ts` 用 `page.route` 攔下來回假資料，不需要後端 |

- **沒有模擬 DOM 的元件測試**：元件裡的規則先抽成純函式（例如 `src/calendar/eventDraft.ts`）用 Vitest 測透，元件只剩畫面和接線，交給 Playwright 在真的瀏覽器裡看。
- **找元素用使用者看得到的東西**：role、label、按鈕上的字，不用 CSS class。名字撞到時用範圍限定（在某一塊裡找），不用 `.first()`、`.last()` —— 那是靠順序，版面一改就點錯而且不會報錯。
- **寫 E2E 之前先打開頁面看**：只看原始碼會猜錯畫面上實際有什麼（手機版有兩個同名的「新增行程」）。
- **時間固定在 2026-10-06 11:30**（`page.clock`）：行事曆到處用到「今天」。
- 版本都釘死（`--save-exact`），挑發布超過兩週的版本。CI 映像檔裡的 Chromium 版本從 `package.json` 讀（`ci/Dockerfile`）。

部署：在 `backend/` 裡 `./gradlew deployWeb`，開 http://localhost:18080。
映像檔是 `Dockerfile` 兩段式（Node build → nginx），nginx 的設定在 `nginx.conf`。

## 壞掉的時候畫面怎麼說（#43）

原則：說**發生什麼事**（用使用者聽得懂的話，不說 503、熔斷），再說**可以怎麼做**；使用者打的字不會不見。
判斷依據是後端分類好的 `code`（#44 的 `AssistantFailure`）和 HTTP 狀態，不看 `error` 的字面。
分類在 `src/calendar/assistantFailure.ts`，畫面在 `AssistantChat.vue`，實測截圖在
[`docs/evidence/2026-10-19-failure-ux/`](../docs/evidence/2026-10-19-failure-ux/README.md)。

**AI 助理**：沒送成功的那句留在對話裡、標「沒送出」；助理那邊是一則紅色邊的說明，附「再試一次」（原封不動再送那句）。

| 情況 | 後端回什麼 | 畫面說 | 可以怎麼做 |
|---|---|---|---|
| 模型連不上（熔斷打開） | 503 `MODEL_UNAVAILABLE`，`retryAfterSeconds` 30 | AI 模型暫時連不上，助理先休息一下。行事曆的其他功能照常可用，可以直接按「新增行程」手動加。 | 倒數 30 秒，倒數完才能再送 |
| 忙碌（同時在等模型的滿了） | 503 `MODEL_BUSY`，5 | 現在問助理的人比較多，忙不過來。等幾秒再試一次。 | 倒數 5 秒 |
| 問太快（nginx 限流） | 429，只有 `Retry-After: 60` header | 問得太快了，先休息一下再問。 | 倒數 60 秒 |
| 這次叫了模型、失敗 | 502 `ASSISTANT_FAILED` | 這次助理沒有回成功（模型出錯或想太久）。可以直接再試一次。 | 馬上可以再試 |
| 請求本身有問題 | 400 `INVALID_REQUEST` | 這句話送不出去：（後端的話） | 沒有「再試一次」；那句放回輸入框讓使用者改 |
| 網路斷了 | 沒有回應 | 連不上伺服器，請確認網路連線後再試一次。 | 馬上可以再試 |
| app 停了、正在重啟 | nginx 自己的 502／504 HTML，沒有 `code` | 伺服器暫時沒有回應（HTTP 502），可能正在更新。等一下再試一次。 | 馬上可以再試 |
| 登入過期 | 401 | （換回登入頁） | 那句存進這個分頁的草稿，重新登入後還在輸入框裡 |
| 等很久 | 還沒回 | 10 秒後「思考中…」改成「還在想，這次比較久（已等 N 秒）…」 | 等；模型最久可能想 2 分鐘 |

- **倒數期間送不出去**：送出鈕、建議說法、「再試一次」都停用，輸入框照樣可以打字（提示改成「N 秒後可以再問，可以先打字」）。
  那段時間送了也一定失敗；一群人同時狂按再試，正是讓模型更起不來的原因
- **不自動重送**：模型一次要好幾秒、雲端上按 token 計費，使用者也可能已經不想問了，由使用者按
- **只有最後一則說明有「再試一次」**：之後又問了別的，舊的那句不會被插回對話中間
- **「行事曆照常可用」只在確定是模型出事時說**：網路斷了、app 停了的時候，行事曆一樣用不了，這樣說是騙人
- **追蹤碼**（#42）照舊附在每則說明底下；請求沒到 app（網路斷、nginx 自己回的）就沒有

**行事曆和整頁**

| 情況 | 以前 | 現在 |
|---|---|---|
| 讀不到行程 | 讀不到行程：HTTP 502 | 讀不到行程：伺服器暫時沒有回應（HTTP 502），請稍後再試＋「重新讀取」；網路回來（`online` 事件）自己重讀 |
| 打開頁面時伺服器連不上 | 等幾秒空白，然後是**登入頁**（登入著的人會以為被登出，去重打密碼，而那時登入一樣失敗） | 1 秒後「正在連線…」，連不上就是「暫時連不上伺服器」＋「重新連線」；只有 401 才是登入頁 |
| 存檔、刪除失敗 | 原因是英文的 Failed to fetch 或 HTTP 502 | 中文原因（`http.ts` 統一翻），表單內容留著可以再按一次 |

## 為什麼這樣選

- **放在 repo 裡、跟 `backend/` 平行，不塞進 `backend/src/main/resources/static`**：前後端的建置工具、生命週期都不同。
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
