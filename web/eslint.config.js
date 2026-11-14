// 只開會出錯的規則，不管格式：格式之後交給 Prettier，還沒接上。選型理由見 ci/README.md 的「lint」。
import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginPlaywright from 'eslint-plugin-playwright'

export default defineConfigWithVueTs(
  { name: 'app/files-to-lint', files: ['**/*.{ts,vue}'] },
  globalIgnores(['dist/**', 'playwright-report/**', 'test-results/**']),

  // 不用 recommended：多出來的大多是寫法偏好和排版
  pluginVue.configs['flat/essential'],
  // 要型別資訊的這組才抓得到忘了 await 的 Promise（no-floating-promises）。代價是多一次型別檢查，這個專案多幾秒
  vueTsConfigs.recommendedTypeChecked,
  // tsconfig.json 只涵蓋 src/，E2E 和設定檔沒有型別資訊，只套用不需要型別的規則；E2E 漏 await 由 Playwright 的規則檢查
  {
    files: ['e2e/**/*.ts', '*.config.ts'],
    extends: [vueTsConfigs.disableTypeChecked],
    languageOptions: { parserOptions: { projectService: false } },
  },
  {
    rules: {
      // HTML 沒有 <icon>，Icon 不會撞名
      'vue/multi-word-component-names': ['error', { ignores: ['Icon'] }],
      // v-html 的內容只要有一點來自使用者（行程標題、AI 的回答）就是 XSS。CodeQL（ci/sast.sh）追不到 .vue 模板裡的 v-html，
      // 所以一律擋。真的要用時（例如 sanitize 過的 markdown），在那一行上面寫 eslint-disable-next-line 並附理由
      'vue/no-v-html': 'error',
    },
  },

  // expect(...) 忘了 await 的話測試照樣綠燈，其實什麼都沒等到
  { ...pluginPlaywright.configs['flat/recommended'], files: ['e2e/**/*.ts'] },
)
