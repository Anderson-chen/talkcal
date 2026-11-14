// 不啟動後端：每個測試用 page.route 把 /api 攔下來回假資料（見 e2e/fakeApi.ts）。
// 不需要資料庫和模型，GitHub 上也能執行，API 失敗的情況一行就造得出來。
// 代價是測不到前後端的格式對不對得上，那由後端的 SystemTest（backend/src/integrationTest）測。
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  // 本機不重試，失敗要馬上看得到
  retries: process.env.CI ? 1 : 0,
  // HTML 報告有每一步的截圖和 trace，本機用 pnpm exec playwright show-report 開啟
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:4173',
    // 行事曆的「今天」和日期顯示都看這兩個設定
    locale: 'zh-TW',
    timezoneId: 'Asia/Taipei',
    trace: 'retain-on-failure',
  },
  // 測打包後的 dist/，跟 nginx 送出去的是同一份
  webServer: {
    command: 'pnpm run build && pnpm exec vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  // 桌面版和手機版是兩套版面（CalendarView 在 760px 切換），操作方式不同，所以測試檔用檔名分開
  projects: [
    { name: 'desktop', testMatch: '**/*.desktop.spec.ts', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } },
    // Pixel 7 用 Chromium 模擬，不必另外安裝 WebKit
    { name: 'mobile', testMatch: '**/*.mobile.spec.ts', use: { ...devices['Pixel 7'] } },
  ],
})
