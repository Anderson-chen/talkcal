// smoke：1 個 VU 執行 3 輪，確認 k6 連得到 AI 助理，而且回應照契約。做負載測試前先執行這支。
//
// 預設送到本機 bootRun 的 app（8090），PostgreSQL 要先啟動：
//   k6 run perf/smoke.js
// 送到別台就覆蓋 BASE_URL：
//   PowerShell:  $env:BASE_URL="http://192.168.0.10:8090"; k6 run perf/smoke.js
//   bash:        BASE_URL=http://192.168.0.10:8090 k6 run perf/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { ASSISTANT_URL, MESSAGES, say, jsonParams, signIn, useSession } from './lib/assistant.js';

export const options = {
  vus: 1,
  iterations: 3,

  thresholds: {
    // 總共才送幾次，錯一次就是真的錯，不是抖動
    checks: ['rate==1.0'],

    // 60 秒只抓卡死，不是效能目標：後面是真的 LLM 在生成，一輪可能呼叫好幾次，
    // Qwen3 的 thinking 還會先用掉幾百個看不見的 token，好幾秒才回來是正常的
    http_req_duration: ['p(95)<60000'],
  },
};

// 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
export function setup() {
  return signIn();
}

export default function (session) {
  useSession(session);
  contractRejectsBlankMessage();
  // 照順序說，不隨機：每次執行的內容一樣
  say(MESSAGES[__ITER % MESSAGES.length]);

  sleep(1);
}

// 空白訊息在讀記憶、呼叫模型之前就回 400，llama.cpp 沒開也會過。
// 模型無法連線時 say 那條紅、這條綠，看得出問題在模型，整條路還是通的
function contractRejectsBlankMessage() {
  // 不標的話 k6 把 400 算進 http_req_failed，報表上的失敗率全是這些故意送錯的請求
  const params = jsonParams({ responseCallback: http.expectedStatuses(400) });
  const res = http.post(ASSISTANT_URL, JSON.stringify({ message: '   ' }), params);
  check(res, {
    '空白訊息回 400': (r) => r.status === 400,
  });
}
