// 沒給 BASE_URL 時改送到 docker 那台 app（deploy/compose.yaml 發佈在 18090）。
// 要在 import lib/assistant.js 之前 import：assistant.js 一載入就讀 BASE_URL。
if (!__ENV.BASE_URL) {
  __ENV.BASE_URL = 'http://localhost:18090';
}
