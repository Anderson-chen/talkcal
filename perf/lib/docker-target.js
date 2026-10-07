// 讓腳本預設打 docker 那台 app（deploy/compose.yaml 發佈在 18090）。
// 只是補預設值：有人明確給了 BASE_URL 就尊重它。
// 要在 import lib/assistant.js 之前先 import 這支，因為 assistant.js 一載入就讀 BASE_URL。
if (!__ENV.BASE_URL) {
  __ENV.BASE_URL = 'http://localhost:18090';
}
