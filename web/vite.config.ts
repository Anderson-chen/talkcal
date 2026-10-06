import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 開發時把 /api 轉給本機 bootRun 的 app。瀏覽器只看到 5173 一個來源，
    // 所以後端不用開 CORS —— 後端根本不知道前端存在，跟 k6、curl 地位一樣。
    // 部署時同一件事改由 nginx 做（deploy/ 那邊），程式碼裡的 /api 一個字都不用改。
    // 目標預設是 bootRun 的 8090；要接到別台（例如同時跑新舊兩版後端時）就設環境變數 EAT_API。
    proxy: {
      '/api': process.env.EAT_API ?? 'http://localhost:8090',
    },
  },
})
