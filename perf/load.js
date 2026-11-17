// load：同時跟助理說話的人數到多少開始排隊。分三段，每段各自有門檻，結束時 k6 分段印出 p95：
//   below：1 個 VU，基準線
//   at：SLOTS 個 VU，剛好等於 llama-server 的 slot 數
//   above：SLOTS×2 個 VU，多的請求只能排隊（requests_deferred 會離開 0）
// 一路往上加的話各段的數字混在一起，比不出來；要每一階都量見 knee.js。
//
// SLOTS 預設 4，是 llama-server 沒帶 -np 時自己決定的 total_slots（GET http://localhost:8080/props 查得到）。
// 改了 -np 就用環境變數對上：
//   PowerShell:  $env:SLOTS="2"; k6 run perf/load.js
//
// 預設送到本機 bootRun 的 app（8090），llama-server 8080、PostgreSQL 都要啟動；全程約 4～5 分鐘：
//   k6 run perf/load.js
// 先確認腳本沒寫錯，每段縮成 10 秒（全程約 2 分鐘）：
//   PowerShell:  $env:HOLD_S="10"; k6 run perf/load.js

import { sleep } from 'k6';
import { MESSAGES, say, signIn, useSession } from './lib/assistant.js';

const SLOTS = Number(__ENV.SLOTS || 4);
// 一個請求要好幾秒，太短的話每段只收到幾筆，p95 不準
const HOLD_S = Number(__ENV.HOLD_S || 60);
// 第一個請求常特別慢（KV cache 還沒暖），給第一個請求獨立的時段
const WARMUP_S = 30;
// constant-vus 時間到時讓手上的請求在 gracefulStop 內完成，不留空檔的話上一段的尾巴會混進下一段
const GAP_S = 20;

const BELOW_START = WARMUP_S;
const AT_START = BELOW_START + HOLD_S + GAP_S;
const ABOVE_START = AT_START + HOLD_S + GAP_S;

function plateau(vus, startS) {
  return {
    executor: 'constant-vus',
    exec: 'talk',
    vus,
    duration: `${HOLD_S}s`,
    startTime: `${startS}s`,
    gracefulStop: `${GAP_S}s`,
  };
}

export const options = {
  scenarios: {
    // 不設門檻，數字不算數
    warmup: {
      executor: 'shared-iterations',
      exec: 'talk',
      vus: 1,
      iterations: 1,
      maxDuration: `${WARMUP_S}s`,
    },
    below: plateau(1, BELOW_START),
    at: plateau(SLOTS, AT_START),
    above: plateau(SLOTS * 2, ABOVE_START),
  },

  thresholds: {
    // 只送正常訊息，非 2xx 都是真的失敗（通常是 llama.cpp 出錯時的 502）。
    // 第二條：失敗率過 10% 就提前結束，server 已經停止回應，再送幾分鐘也只量到 502
    http_req_failed: ['rate<0.01', { threshold: 'rate<0.1', abortOnFail: true, delayAbortEval: '30s' }],
    checks: ['rate>0.99'],

    // 結束時的報表只印有設門檻的子指標，這三條拿掉的話就看不到各段的 p95。
    // 30 秒是暫定的服務目標，還沒照實測的數字調過
    'http_req_duration{scenario:below}': ['p(95)<30000'],
    'http_req_duration{scenario:at}': ['p(95)<30000'],
    // 超過容量本來就會變慢，60 秒只抓卡住
    'http_req_duration{scenario:above}': ['p(95)<60000'],
  },
};

// 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
export function setup() {
  return signIn();
}

export function talk(session) {
  useSession(session);
  // 照順序的話，多個 VU 會在同一刻說同一句
  say(MESSAGES[Math.floor(Math.random() * MESSAGES.length)]);

  // 1 秒遠小於一次回覆的好幾秒，VU 數 ≈ 同時壓在 llama-server 上的請求數，才能直接跟 SLOTS 比
  // （助理一輪裡的幾次模型呼叫是一個接一個，同一刻只占一個 slot）
  sleep(1);
}
