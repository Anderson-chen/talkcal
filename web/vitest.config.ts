// mergeConfig 沿用 vite.config.ts 的 Vue 外掛，測試 import 到 .vue 時跟打包用同一種轉換。
//
// Vitest 只測純函式，在 Node 裡執行，不模擬瀏覽器。元件的行為由 Playwright 在真的瀏覽器裡測（e2e/）：
// 邏輯先抽成純函式（例如 calendar/eventDraft.ts），元件只剩畫面和串接，要在真的瀏覽器裡看才準。
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // 不寫的話 Vitest 預設也收 *.spec.ts，會把 Playwright 的 e2e/*.spec.ts 也拿來執行
      include: ['src/**/*.test.ts'],
      environment: 'node',
      // 用法見 docs/test-coverage.md。由 pnpm test 的 --coverage 開啟，不寫 enabled: true：
      // 寫了的話 test:watch 每存一次檔都重新產生報告、清掉終端機的輸出
      coverage: {
        // v8 用 Node 內建的覆蓋率，不必在程式碼裡另外加記錄用的程式（instrumentation）。Vitest 4 起靠 AST 重新對映，準確度跟 istanbul 相同
        provider: 'v8',
        // Vitest 4 拿掉了 coverage.all，不寫 include 的話，沒有任何測試 import 的檔案不會出現在報告裡，
        // 那些正是要找的漏測。只算 .ts：.vue 元件由 Playwright 測
        include: ['src/**/*.ts'],
        exclude: ['src/main.ts'],
        // json-summary 由 ci/checks.sh 讀總數、貼進摘要
        reporter: ['text-summary', 'html', 'json-summary'],
      },
    },
  }),
)
