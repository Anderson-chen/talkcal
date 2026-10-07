// load —— 找出「同時幾個人在跟助理說話，就開始塞車」。
//
// smoke 證明路是通的；這支回答下一個問題：併發變多時，延遲怎麼變？
//
// 刻意設計成三段平台（plateau），而不是一路往上加：
//   below —— 1 個 VU：低於容量，當基準線
//   at    —— SLOTS 個 VU：剛好等於 llama-server 的 slot 數，每個請求都有位子
//   above —— SLOTS×2 個 VU：超過容量，多出來的請求只能排隊（requests_deferred 會離開 0）
// 每段各自維持一陣子、各自有門檻，收工時 k6 會分段印出 p95 ——
// 不必看圖，直接比三個數字就知道「膝蓋」在哪。一路往上加的話，數字全混在一起比不出來。
//
// SLOTS 預設 4：這是 llama-server 目前的 total_slots（GET http://localhost:8080/props 可查）。
// launch.json 沒帶 -np，所以是 llama.cpp 自己決定的預設值；改了 -np 就用環境變數對上：
//   PowerShell:  $env:SLOTS="2"; k6 run perf/load.js
//
// 怎麼跑（app 8090、llama-server 8080、PostgreSQL 都要在跑；全程約 4~5 分鐘）：
//   k6 run perf/load.js
// 只想先確認腳本本身沒寫錯，把每段縮成 10 秒（全程約 2 分鐘）：
//   PowerShell:  $env:HOLD_S="10"; k6 run perf/load.js
// 邊跑邊開 Grafana 的 llama.cpp 與 eat app 兩張 dashboard 對照看，最有感。

import { sleep } from 'k6';
import { MESSAGES, say } from './lib/assistant.js';

const SLOTS = Number(__ENV.SLOTS || 4);
// 每段平台維持多久。一個請求要好幾秒，太短的話每段只收到幾筆，p95 會很不準
const HOLD_S = Number(__ENV.HOLD_S || 60);
// 暖身窗：第一個請求常特別慢（暖 KV cache 等），給它獨立的時段，不混進任何一段的數字
const WARMUP_S = 30;
// 段與段之間的空檔。constant-vus 時間到時不會砍掉手上的請求，而是讓它在 gracefulStop 內跑完；
// 一個請求要好幾秒，不留空檔的話上一段的尾巴會混進下一段的數字裡
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
    // 暖身只說一句，而且不設任何門檻 —— 它的數字不算數
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
    // 這支只送正常訊息，所以任何非 2xx 都是真的失敗（通常是 502 = llama.cpp 那頭出事）。
    // 第二條是保險絲：失敗率衝過 10% 就提前收工 ——
    // 對著一台已經倒下的 server 再打好幾分鐘，除了一堆 502 什麼也量不到。
    http_req_failed: ['rate<0.01', { threshold: 'rate<0.1', abortOnFail: true, delayAbortEval: '30s' }],
    checks: ['rate>0.99'],

    // 分段門檻。k6 有個小規矩：收工報表只印「有設門檻的」子指標，
    // 所以這三條除了判成敗，也是讓報表分段印出各平台 p95 的開關。
    // 30 秒是暫定的服務目標（使用者願意等多久），跑過一輪看到實際數字再調。
    'http_req_duration{scenario:below}': ['p(95)<30000'],
    'http_req_duration{scenario:at}': ['p(95)<30000'],
    // 超過容量本來就會變慢，這段只要求「別卡死」：用跟 smoke 一樣的離譜線
    'http_req_duration{scenario:above}': ['p(95)<60000'],
  },
};

export function talk() {
  // 隨機挑而不是照順序：多個 VU 同時跑時，照順序會讓大家在同一刻說同一句
  say(MESSAGES[Math.floor(Math.random() * MESSAGES.length)]);

  // 思考時間 1 秒：模擬真人看完回覆再說下一句。
  // 它遠小於一次回覆要的好幾秒，所以「VU 數」≈「同時壓在 llama-server 上的請求數」，
  // 才能直接拿來跟 SLOTS 比（助理一輪裡的幾次模型呼叫是一個接一個，同一刻只占一個 slot）。
  sleep(1);
}
