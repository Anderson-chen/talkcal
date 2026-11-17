import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 打包進來的套件授權全文寫成 dist/licenses.txt：MIT、BSD 要求散布時附上版權和授權聲明，
    // 送到使用者瀏覽器的 js 就是散布（見 docs/licenses.md）。用 .txt：nginx 不會把 .md 當成文字顯示，會當成下載檔
    license: { fileName: 'licenses.txt' },
  },
  server: {
    // 開發時把 /api 轉給本機的 app，瀏覽器只看到 5173 一個來源，後端不必開 CORS；部署時由 nginx.conf 做同一件事。
    // 要接到別的後端（例如同時執行新舊兩版）就設環境變數 EAT_API。
    proxy: {
      '/api': {
        target: process.env.EAT_API ?? 'http://localhost:8090',
        // 保留瀏覽器送來的 Host（localhost:5173）。proxy 寫成字串簡寫會打開 changeOrigin、Host 換成 8090，
        // 後端用 Host 組出的 Google、GitHub 回呼網址就跟登記的 5173 對不上（redirect_uri_mismatch）
        changeOrigin: false,
      },
    },
  },
})
