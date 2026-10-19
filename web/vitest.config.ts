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
      // 只找 src（前端）和 dev（開發工具，例如 vite 的 proxy 轉去哪）底下的 *.test.ts。
      // 不寫的話 Vitest 預設也會撿 *.spec.ts，會把 Playwright 的測試（e2e/*.spec.ts）也拿來跑
      include: ['src/**/*.test.ts', 'dev/**/*.test.ts'],
      environment: 'node',
    },
  }),
)
