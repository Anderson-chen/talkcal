// Playwright 的設定：用真的瀏覽器（Chromium）開整個前端，像使用者一樣點。
// 元件的行為在這裡測（Vitest 只測純函式，見 vitest.config.ts）。
//
// 後端不開：每個測試用 page.route 把 /api 攔下來回假資料（見 e2e/fakeApi.ts）。
// 這樣不需要資料庫和模型、GitHub 上也能跑，「API 失敗」這種情況一行就造得出來。
// 代價是測不到前後端格式對不對得上 —— 那是後端 SystemTest 的事。
import { execFileSync } from 'node:child_process'
import { defineConfig, devices } from '@playwright/test'

// 埠：每次跑挑一個空的，不寫死 4173（#109）。寫死的話兩個 worktree 同時跑 E2E，第二個的 vite preview 起不來
// （strictPort），或者更糟 —— 拿掉 strictPort、reuseExistingServer 又打開時，會直接拿別的 worktree 開著的那份來測。
// 這個設定檔每個 worker 程序都會重新載入一次，所以挑好的埠放進環境變數：worker 是主程序開的，繼承同一個值。
// 想固定（例如 vite preview 已經自己開著、要重用）就自己設 E2E_PORT。
// 用子程序挑：設定檔要同步算出來，Node 的 listen 是非同步的
if (!process.env.E2E_PORT) {
  process.env.E2E_PORT = execFileSync(process.execPath, ['-e',
    "const s = require('node:net').createServer().listen(0, '127.0.0.1', () => { process.stdout.write(String(s.address().port)); s.close() })",
  ]).toString()
  process.env.E2E_PORT_PICKED = '1'
}
const port = Number(process.env.E2E_PORT)
const baseURL = `http://localhost:${port}`

export default defineConfig({
  testDir: 'e2e',
  // 一個測試失敗時，CI 上重試一次也還是失敗才算數；本機不重試，失敗要馬上看得到
  retries: process.env.CI ? 1 : 0,
  // 失敗時的報告：HTML 報告裡有每一步的截圖和 trace（時間軸、DOM 快照、網路請求），本機用 npx playwright show-report 打開
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    // 跟使用者一樣用台北時間、繁體中文：行事曆的「今天」和日期顯示都看這兩個
    locale: 'zh-TW',
    timezoneId: 'Asia/Taipei',
    // 失敗才留 trace：記下每一步的畫面和網路，事後一步一步重播，不必在本機重現
    trace: 'retain-on-failure',
  },
  // 測的是打包後的成品（跟 nginx 送出去的是同一份 dist/），不是開發用的 dev server
  webServer: {
    // strictPort：埠是上面挑好的空埠，萬一剛好被搶走就直接失敗，不讓 vite 自己往上找到別號（那樣 baseURL 就對不上了）
    command: `npm run build && npx vite preview --port ${port} --strictPort`,
    url: baseURL,
    // 自己設了 E2E_PORT、那一號又已經開著，才直接用不重開。自己挑的埠一律從頭起：
    // 挑完到 preview 起來之間，那一號可能剛好被別的 worktree 拿走，重用的話就是在測別人的 dist/。CI 每次都從頭建
    reuseExistingServer: !process.env.CI && !process.env.E2E_PORT_PICKED,
    timeout: 120_000,
  },
  // 桌面版和手機版是兩套版面（CalendarView 用 matchMedia 在 760px 切），操作的方式不一樣：
  // 桌面是左側欄的按鈕＋對話框，手機是右下角的浮動按鈕＋整頁表單。所以測試檔用檔名分，各跑各的
  projects: [
    { name: 'desktop', testMatch: '**/*.desktop.spec.ts', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } },
    // Pixel 7：412px 寬、觸控、手機的 user agent。用 Chromium 模擬，不必另外裝 Safari（WebKit）
    { name: 'mobile', testMatch: '**/*.mobile.spec.ts', use: { ...devices['Pixel 7'] } },
  ],
})
