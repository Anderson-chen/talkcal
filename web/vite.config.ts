import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 打包進來的套件（vue 和它的依賴）授權全文寫成 dist/licenses.txt，跟著網站一起送出去：
    // MIT、BSD 都要求散布時附上版權和授權聲明，而前端的 js 正是送到每個使用者瀏覽器的「散布」（見 docs/licenses.md）。
    // 用 .txt 不用 .md：nginx 不認得 .md，會當成下載檔
    license: { fileName: 'licenses.txt' },
  },
  server: {
    // 開發時把 /api 轉給本機 bootRun 的 app。瀏覽器只看到 5173 一個來源，
    // 所以後端不用開 CORS —— 後端根本不知道前端存在，跟 k6、curl 地位一樣。
    // 部署時同一件事改由 nginx 做（deploy/ 那邊），程式碼裡的 /api 一個字都不用改。
    // 目標預設是 bootRun 的 8090；要接到別台（例如同時跑新舊兩版後端時）就設環境變數 EAT_API。
    proxy: {
      '/api': {
        target: process.env.EAT_API ?? 'http://localhost:8090',
        // 保留瀏覽器送來的 Host（localhost:5173），不換成 8090。字串簡寫會把 changeOrigin 打開、Host 改成 target 的，
        // 後端用 Host 組 Google、GitHub 的回呼網址，就變成 localhost:8090/...，跟登記的 5173 對不上（redirect_uri_mismatch）
        changeOrigin: false,
      },
    },
  },
})
