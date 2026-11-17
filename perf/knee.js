// knee：同時人數從 1 一階一階往上加，找吞吐量不再增加、延遲開始往上衝的那一階（臨界值）。
// 結束時印一張每階一列的表。
//
// 每一階是 constant-vus、說完立刻再說（不 sleep），VU 數 = 持續壓在 server 上的請求數。
//
// 預設送到 Docker 裡的 app（18090），全程約 STEPS 階數 × (HOLD_S + GAP_S) 秒：
//   k6 run perf/knee.js
// 先確認腳本沒寫錯，每階縮成 10 秒：
//   PowerShell:  $env:HOLD_S="10"; k6 run perf/knee.js
// 自訂要量哪幾階：
//   PowerShell:  $env:STEPS="1,4,8,16,32"; k6 run perf/knee.js
// 換一句話（每個請求都說這一句）：
//   PowerShell:  $env:MESSAGE="這週末有空嗎？"; k6 run perf/knee.js

import './lib/docker-target.js';
import { say, signIn, useSession } from './lib/assistant.js';

// 每個請求都說同一句：各句的成本差很多（查行程呼叫兩次模型，提議行程還要多抽一次），
// 隨機挑的話延遲取決於這一階抽到幾句貴的，人數的影響被蓋掉。預設是一句只走 list_events 的查詢。
// 同一句會命中 llama.cpp 的 prompt 快取，但 prefill 只占十幾毫秒，可以忽略
const MESSAGE = __ENV.MESSAGE || '明天有什麼行程？';
const STEPS = (__ENV.STEPS || '1,2,3,4,5,6,8,12,16').split(',').map(Number);
// 預設 5 秒，整輪比較快結束，但助理一輪要好幾秒，5 秒只收得到幾筆，中位數不穩定；要穩定的數字就拉長
const HOLD_S = Number(__ENV.HOLD_S || 5);
// 上一階還沒回來的請求會在 gracefulStop 內完成，不留空檔就會混進下一階
const GAP_S = Number(__ENV.GAP_S || 4);

const scenarios = {};
const thresholds = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};
STEPS.forEach((vus, i) => {
  const name = `c${vus}`;
  scenarios[name] = {
    executor: 'constant-vus',
    vus,
    duration: `${HOLD_S}s`,
    startTime: `${i * (HOLD_S + GAP_S)}s`,
    gracefulStop: `${GAP_S}s`,
  };
  // 不是要判成敗：只有設了門檻的子指標，handleSummary 才拿得到
  thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
});

export const options = { scenarios, thresholds };

// 第一個請求常特別慢，在 setup 裡先說一句，不算進任何一階
export function setup() {
  // 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
  const session = signIn();
  useSession(session);
  say(MESSAGE);
  return session;
}

export default function (session) {
  useSession(session);
  say(MESSAGE);
}

export function handleSummary(data) {
  const rows = STEPS.map((vus) => {
    const d = data.metrics[`http_req_duration{scenario:c${vus}}`].values;
    const n = data.metrics[`http_reqs{scenario:c${vus}}`].values.count;
    return { vus, med: d.med, p95: d['p(95)'], n, rps: n / HOLD_S };
  });
  const base = rows[0];
  const lines = [
    '',
    '  同時數 |  中位延遲 |      p95 |  請求數 |  吞吐量(個/秒) | 延遲是 1 人時的幾倍',
    '  -------+-----------+----------+---------+----------------+-------------------',
    ...rows.map((r) =>
      [
        String(r.vus).padStart(7),
        `${Math.round(r.med)}ms`.padStart(10),
        `${Math.round(r.p95)}ms`.padStart(9),
        String(r.n).padStart(8),
        r.rps.toFixed(2).padStart(15),
        `${(r.med / base.med).toFixed(1)}x`.padStart(19),
      ].join(' |'),
    ),
    '',
  ];
  return { stdout: lines.join('\n') + '\n' };
}
