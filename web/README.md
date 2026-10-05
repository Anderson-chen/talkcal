# web

eat 的前端：Vue 3 + TypeScript + Vite。只是 `POST /api/chat` 的另一個呼叫端，後端不知道它存在。

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
  `/api/chat` 的契約（`src/chat.ts` ↔ `ChatController` 的 record）改的時候要同一個 commit 一起改。
- **用 proxy 不用 CORS**：開發時 Vite 轉 `/api`，部署時 nginx 轉，瀏覽器永遠只看到一個來源。
- **TypeScript 釘在 5.9**：TS 7 是 Go 重寫的版本，沒有舊的 JS API，vue-tsc 3 還接不上
  （會噴 `Package subpath './lib/tsc' is not defined`）。等 vue-tsc 支援再升。
- **非串流**：`/api/chat` 一次回完整回覆；要逐字顯示得先改後端的 port / adapter。
