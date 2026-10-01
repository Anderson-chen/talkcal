// concurrent —— USERS 個人在 WINDOW_S 秒內陸續湧進來，每人只問一次。
//
// 跟 knee.js 的差別：knee 是「固定 N 個人一直問」，量穩定狀態；
// 這支是「一波人在短時間內各問一次就走」，像活動開始、大家同時打開網頁的那種尖峰。
// 一個 /api/chat 會依序經過 embedding（8081，檢索知識庫）和 llm-chat（8080，生成），
// 所以只打這一支，兩台模型伺服器就都被壓到了。
//
// 用 constant-arrival-rate：k6 照固定節奏「開始」新的請求，不管前面的回來了沒。
// 預設 100 人 / 10 秒 = 每 0.1 秒來一個人，平均分散在 10 秒內，不是第 0 秒一次全進來。
// 每一輪只送一個請求，所以「輪數」=「人數」= 請求數。
//
// 怎麼看結果：
//   http_req_duration —— 每個人等了多久。越後面來的人，前面排隊的越多，max 會遠大於 min
//   dropped_iterations —— 有出現就代表 k6 的 VU 不夠用，沒送到你設定的人數，數字不能信
//
// 預設打 docker 那台 app（deploy/compose.yaml 發佈在 18090）。
// 要打本機 bootRun 的就覆蓋 BASE_URL：
//   PowerShell:  $env:BASE_URL="http://localhost:8090"; k6 run perf/concurrent.js
// 人數與時間窗都能調：
//   PowerShell:  $env:USERS="50"; $env:WINDOW_S="5"; k6 run perf/concurrent.js

// lib/chat.js 在 import 時就讀 BASE_URL，所以預設值要在這裡、import 之前補上
import './lib/docker-target.js';
import { QUESTIONS, ask } from './lib/chat.js';

const USERS = Number(__ENV.USERS || 1000);
const WINDOW_S = Number(__ENV.WINDOW_S || 30);

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      // 每 WINDOW_S 秒開始 USERS 輪，持續 WINDOW_S 秒 → 剛好 USERS 輪
      rate: USERS,
      timeUnit: `${WINDOW_S}s`,
      duration: `${WINDOW_S}s`,
      // 一個 VU 一次只能送一個請求。最壞情況是所有人都還在排隊沒回來，
      // 所以 VU 要備到跟人數一樣多，否則 k6 會跳過那幾輪（記在 dropped_iterations）
      preAllocatedVUs: USERS,
      maxVUs: USERS,
      // 最後一個人在第 WINDOW_S 秒才進來，還要排隊加生成；給足時間讓大家都拿到回覆
      gracefulStop: '120s',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    // 沿用 load.js 的離譜線：尖峰時最後幾個人本來就會等比較久，這裡只要求別卡死
    http_req_duration: ['p(95)<60000'],
  },
};

// 暖身：第一個請求常特別慢（暖 KV cache 等）。在 setup 裡跑掉，不算進這一波的數字
export function setup() {
  ask(QUESTIONS[0]);
}

export default function () {
  ask(QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)]);
}
