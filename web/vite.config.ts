import { fileURLToPath } from 'node:url'
import type { ServerResponse } from 'node:http'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { backendTarget } from './dev/backendTarget'

// 這個資料夾自己的後端把埠寫在哪（backend/build.gradle 的 bootRun）。每個 worktree 各有一份，見 dev/backendTarget.ts
const backendBuildDir = fileURLToPath(new URL('../backend/build', import.meta.url))

// 回 {"error": ...}：src/http.ts 會把它當錯誤訊息顯示在畫面上，不是只看到「HTTP 502」
function noBackend(res: ServerResponse, error: string) {
  if (res.headersSent || res.writableEnded) return
  res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify({ error }))
}

export default defineConfig({
  plugins: [vue()],
  server: {
    // 5173 被佔了（別的 worktree 也開著 dev server）就往上找 5174、5175…，不失敗。
    // 這是 vite 本來的行為，寫出來是因為它有代價：Google、GitHub 登入登記的回呼網址是 5173，
    // 拿到別號的那個 worktree 只能用帳密登入（docs/parallel-dev.md）
    port: 5173,
    strictPort: false,
    // 開發時把 /api 轉給本機 bootRun 的 app。瀏覽器只看到 5173 一個來源，
    // 所以後端不用開 CORS —— 後端根本不知道前端存在，跟 k6、curl 地位一樣。
    // 部署時同一件事改由 nginx 做（deploy/ 那邊），程式碼裡的 /api 一個字都不用改。
    proxy: {
      '/api': {
        // 保留瀏覽器送來的 Host（localhost:5173），不換成後端的。changeOrigin 打開的話 Host 會改成 target 的，
        // 後端用 Host 組 Google、GitHub 的回呼網址，就變成 localhost:8090/...，跟登記的 5173 對不上（redirect_uri_mismatch）
        changeOrigin: false,
        // 轉去哪不是寫死的：後端的埠每次 bootRun 可能不同（#109），所以每個請求都重新看一次 backend/build/application.port。
        // vite 的 proxy 只有啟動時給一次的 target，這裡把它的 web() 包一層，每次帶上當下的 target
        configure(proxy) {
          const web = proxy.web.bind(proxy)
          proxy.web = ((req, res, options, ...rest) => {
            const target = backendTarget(backendBuildDir)
            if ('error' in target) return noBackend(res, target.error)
            return web(req, res, { ...options, target: target.url }, ...rest)
          }) as typeof proxy.web
          // 埠檔、pid 都對，但連不上（app 還在啟動、正在關機）：一樣回看得懂的訊息。vite 自己的處理只回空的 502
          proxy.on('error', (err, _req, res) => {
            if ('writeHead' in res) noBackend(res, `連不上後端（${err.message}）：bootRun 可能還在啟動，等它印出 Started Application 再試`)
          })
        },
      },
    },
  },
})
