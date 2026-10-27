// 測試的設定。跟打包的 vite.config.ts 分開放：打包不需要知道測試的事。
// mergeConfig 沿用 vite.config.ts（Vue 外掛等），測試 import 到 .vue 時跟開發、打包是同一種轉法。
//
// Vitest 只測純函式（src/**/*.test.ts，在 Node 裡跑，不模擬瀏覽器）。
// 元件的行為交給 Playwright 用真的瀏覽器測（e2e/），不在這裡掛元件：
// 邏輯先抽成純函式（例如 calendar/eventDraft.ts），元件只剩畫面和接線，接線要在真的瀏覽器裡看才準。
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // 只找 src 底下的 *.test.ts。不寫的話 Vitest 預設也會撿 *.spec.ts，
      // 會把 Playwright 的測試（e2e/*.spec.ts）也拿來跑
      include: ['src/**/*.test.ts'],
      environment: 'node',
      // 測試覆蓋率（#23），用法見 docs/test-coverage.md。由 npm test 的 --coverage 打開，
      // 不寫 enabled: true：那樣 test:watch 每存一次檔都會重產報告、洗版終端機，watch 時用不到
      coverage: {
        // v8：用 Node 內建的覆蓋率，不必先插樁（istanbul 要），Vitest 4 起靠 AST 重新對映，準確度跟 istanbul 一樣
        provider: 'v8',
        // 要明寫 include：Vitest 4 拿掉了 coverage.all，不寫的話「沒有任何測試 import 的檔案」不會出現在報告裡 ——
        // 偏偏那些正是要找的漏測。只算 .ts：.vue 元件交給 Playwright 測（見上面）
        include: ['src/**/*.ts'],
        // main.ts 只有掛載 app 的接線
        exclude: ['src/main.ts'],
        // text-summary 印在終端機；html 給人點進去看哪一行沒跑到；json-summary 給 ci/checks.sh 讀總數貼摘要
        reporter: ['text-summary', 'html', 'json-summary'],
      },
    },
  }),
)
