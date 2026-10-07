// knee —— 同時數從 1 一路往上加，找出「再加人也不會更快、只會更慢」的那一點（臨界值）。
//
// load.js 只看三段（1、SLOTS、SLOTS×2）；這支把每一階都量出來，畫成一張表：
//   同時數 | 中位延遲 | p95 | 每秒做完幾個（吞吐量）
// 臨界值長這樣 —— 吞吐量停止增加，延遲開始往上衝：
//   低於臨界值：多一個人，吞吐量跟著漲，延遲幾乎不變（server 還有空位）
//   超過臨界值：多一個人，吞吐量不動，延遲直接加上去（多的人只是在排隊）
//
// 每一階都是 constant-vus、說完立刻再說（不 sleep），讓「VU 數」= 「持續壓在 server 上的請求數」。
// 跟 concurrent.js 的差別：那支量「一瞬間湧進 N 個」，這支量「持續有 N 個在用」的穩定狀態。
//
// 預設打 docker 那台 app（18090），全程約 STEPS 階數 × (HOLD_S + GAP_S) 秒：
//   k6 run perf/knee.js
// 先確認腳本沒寫錯，每階縮成 10 秒：
//   PowerShell:  $env:HOLD_S="10"; k6 run perf/knee.js
// 自訂要量哪幾階：
//   PowerShell:  $env:STEPS="1,4,8,16,32"; k6 run perf/knee.js
// 換一句話（每個請求都說這一句）：
//   PowerShell:  $env:MESSAGE="這週末有空嗎？"; k6 run perf/knee.js

import './lib/docker-target.js';
import { say } from './lib/assistant.js';

// 每個請求都說同一句，不隨機挑。這支要量的是「同時人數」的影響，
// 而各句的成本差很多（查行程叫兩次模型，提議新行程還要多抽一次行程），
// 隨機挑的話，延遲主要取決於「這一階剛好抽到幾句貴的」，人數的影響反而被蓋掉。
// 預設挑一句查詢：走一次工具（list_events）、不提議，成本比較穩。
// 同一句會命中 llama.cpp 的 prompt 快取，但 prefill 只占十幾毫秒，可以忽略。
const MESSAGE = __ENV.MESSAGE || '明天有什麼行程？';
const STEPS = (__ENV.STEPS || '1,2,3,4,5,6,8,12,16').split(',').map(Number);
// 每階維持多久。預設 5 秒，跑一輪比較快；但助理一輪要好幾秒，5 秒裡只收得到幾筆，
// 中位數會抖。要更穩的數字就拉長：$env:HOLD_S="20"
const HOLD_S = Number(__ENV.HOLD_S || 5);
// 階與階之間的空檔：上一階還在排隊的請求會在 gracefulStop 內跑完，不留空檔就會混進下一階
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
  // 這裡的門檻不是要判成敗，是 k6 的規矩：只有設了門檻的子指標，handleSummary 才拿得到
  thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
});

export const options = { scenarios, thresholds };

// 暖身：第一個請求常特別慢，在 setup 裡跑掉，不算進任何一階
export function setup() {
  say(MESSAGE);
}

export default function () {
  say(MESSAGE);
}

// 收工時印一張「每一階一列」的表，直接看哪一列開始吞吐量不漲、延遲衝上去
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
