// ESLint 只抓「會出錯的寫法」，不管格式（縮排、引號、換行）：格式之後交給 Prettier 一次全面排好（#179）。
// 選型理由見 ci/README.md 的「lint」。
import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginPlaywright from 'eslint-plugin-playwright'

export default defineConfigWithVueTs(
  { name: 'app/files-to-lint', files: ['**/*.{ts,vue}'] },
  globalIgnores(['dist/**', 'playwright-report/**', 'test-results/**']),

  // essential：只開「一定是錯」的 Vue 規則（v-for 沒有 key、改到 props…）。
  // 不用 recommended：它多出來的大多是寫法偏好和排版，排版之後歸 Prettier
  pluginVue.configs['flat/essential'],
  // 要型別資訊的那一組：能抓到忘了 await 的 Promise（no-floating-promises）這類只看語法看不出來的錯。
  // 代價是要跑一次型別檢查，這個大小的專案多幾秒而已
  vueTsConfigs.recommendedTypeChecked,
  // 型別資訊來自 tsconfig.json，它只涵蓋 src/（vue-tsc 檢查的範圍）。E2E 和設定檔不在裡面，
  // 對它們只跑不需要型別的規則；E2E 漏 await 由下面的 Playwright 規則抓
  {
    files: ['e2e/**/*.ts', '*.config.ts'],
    extends: [vueTsConfigs.disableTypeChecked],
    languageOptions: { parserOptions: { projectService: false } },
  },
  {
    rules: {
      // 元件名要兩個字以上，免得跟 HTML 原生標籤撞名。Icon 不會撞（沒有 <icon>），改名只是製造 diff
      'vue/multi-word-component-names': ['error', { ignores: ['Icon'] }],
      // v-html 把字串當 HTML 插進頁面，內容只要有一點來自使用者（行程標題、AI 的回答）就是 XSS。
      // CodeQL（ci/sast.sh）追不到 .vue 模板裡的 v-html（#235 實測），所以在這裡一律擋，不管內容從哪來。
      // 真的要用時（例如 sanitize 過的 markdown），在那一行上面寫 eslint-disable-next-line 並附理由
      'vue/no-v-html': 'error',
    },
  },

  // E2E：Playwright 最常見的錯是 expect(...) 忘了 await —— 測試照樣綠，其實什麼都沒等到
  { ...pluginPlaywright.configs['flat/recommended'], files: ['e2e/**/*.ts'] },
)
