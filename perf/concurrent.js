// concurrent：USERS 個人在 WINDOW_S 秒內陸續進來，每人跟助理說一句就走，量活動開始那種尖峰。
// knee.js 量的是固定 N 個人一直說的穩定狀態。
// 一個助理請求會叫模型、讀寫 PostgreSQL，兩邊一起壓到。預設 1000 人會留下 1000 段對話（見 lib/assistant.js）。
//
// constant-arrival-rate：k6 照固定節奏開始新的一輪，不等前面的回來，所以人數 = 輪數 = 請求數。
// 結果裡出現 dropped_iterations，代表 VU 不夠、沒送到設定的人數，數字不能信。
//
// 預設打 docker 那台 app（18090），打本機 bootRun 的就覆蓋 BASE_URL：
//   PowerShell:  $env:BASE_URL="http://localhost:8090"; k6 run perf/concurrent.js
// 調人數和時間窗：
//   PowerShell:  $env:USERS="50"; $env:WINDOW_S="5"; k6 run perf/concurrent.js

import './lib/docker-target.js';
import { MESSAGES, say, signIn, useSession } from './lib/assistant.js';

const USERS = Number(__ENV.USERS || 1000);
const WINDOW_S = Number(__ENV.WINDOW_S || 60);

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      // 每 WINDOW_S 秒開始 USERS 輪、持續 WINDOW_S 秒，剛好 USERS 輪
      rate: USERS,
      timeUnit: `${WINDOW_S}s`,
      duration: `${WINDOW_S}s`,
      // 一個 VU 一次只送一個請求，最壞情況是所有人都還沒回來。VU 比人數少的話 k6 會跳過那幾輪
      preAllocatedVUs: USERS,
      maxVUs: USERS,
      // 最後一個人在第 WINDOW_S 秒才進來，還要排隊和生成
      gracefulStop: '120s',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    // 只抓卡死：尖峰時最後來的人本來就會等很久
    http_req_duration: ['p(95)<60000'],
  },
};

// 第一個請求常特別慢（KV cache 還沒暖），在 setup 裡先說一句，不算進這一波
export function setup() {
  // 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
  const session = signIn();
  useSession(session);
  say(MESSAGES[0]);
  return session;
}

export default function (session) {
  useSession(session);
  say(MESSAGES[Math.floor(Math.random() * MESSAGES.length)]);
}
