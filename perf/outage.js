// outage —— 模型掛掉演練（#44）：模型斷線或卡住的時候，助理是不是快速失敗、行程 API 是不是照常可用。
//
// 同時跑兩群人：
//   assistant —— 每分鐘 RATE_PER_MIN 個人跟助理說一句（跟 deploy.js 一樣用 constant-arrival-rate：
//                模型掛了也不會因此多來人，數字才能直接比）
//   calendar  —— 每秒 1 個人讀一個月的行程（GET /api/calendar/events），不經過模型
//
// 故障由人在另一個終端機製造（k6 不知道模型什麼時候掛），每一句的結果都印一行，對著時間看：
//   斷線：docker stop <模型容器>     → 連不上，幾毫秒就失敗
//   卡住：docker pause <模型容器>    → 連得上、但永遠不回，要等到逾時（2 分鐘）才失敗
//   恢復：docker start／docker unpause
//
// 收工印一張表：助理的每種結果（200、502、503 的兩種 code）各幾個、各花多久；行程 API 成功幾個、p95 多久。
// 預期（門檻、理由在 application.properties 的 resilience4j.*（chat-model））：
//   - 模型掛掉後，前 5 次失敗讓熔斷打開；之後助理一律回 503 MODEL_UNAVAILABLE，幾毫秒
//   - 卡住的時候還在等的請求最多 50 個（bulkhead），再多就回 503 MODEL_BUSY
//   - 行程 API 從頭到尾都是 200
//   - 模型恢復後 30 秒內熔斷試探、關回來，助理又回 200
//
// 怎麼跑（打哪台用 BASE_URL，預設是 docker 那台 18090）：
//   k6 run perf/outage.js
//   $env:DURATION_S="600"; k6 run perf/outage.js        # 演練「卡住」要更久：要等 2 分鐘逾時

import './lib/docker-target.js';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { ASSISTANT_URL, BASE_URL, MESSAGES, jsonParams, signIn, useSession } from './lib/assistant.js';

const RATE_PER_MIN = Number(__ENV.RATE_PER_MIN || 20); // 要低於容量，理由同 deploy.js
const DURATION_S = Number(__ENV.DURATION_S || 300);

// 助理的結果依 code 分開記：200、502（這次失敗）、503 的兩種（沒叫模型）、其他（含連線失敗）
const OUTCOMES = {
  ok: '200 正常回覆',
  ASSISTANT_FAILED: '502 ASSISTANT_FAILED（叫了、失敗）',
  MODEL_UNAVAILABLE: '503 MODEL_UNAVAILABLE（熔斷，沒叫模型）',
  MODEL_BUSY: '503 MODEL_BUSY（滿載，沒叫模型）',
  other: '其他（含沒拿到回應）',
};
// Trend 只有分佈（中位數、最久…），沒有次數，次數另外數
const assistantMs = Object.fromEntries(Object.keys(OUTCOMES).map((k) => [k, new Trend(`outage_assistant_${k}`, true)]));
const assistantCount = Object.fromEntries(Object.keys(OUTCOMES).map((k) => [k, new Counter(`outage_assistant_${k}_count`)]));
const calendarMs = new Trend('outage_calendar', true);
const calendarCount = new Counter('outage_calendar_count');
const calendarFailed = new Counter('outage_calendar_failed');

export const options = {
  scenarios: {
    assistant: {
      executor: 'constant-arrival-rate',
      exec: 'assistant',
      rate: RATE_PER_MIN,
      timeUnit: '1m',
      duration: `${DURATION_S}s`,
      // 模型卡住時一個請求佔住一個 VU 2 分鐘，備足才不會 dropped_iterations
      preAllocatedVUs: 50,
      maxVUs: 300,
      gracefulStop: '150s',
    },
    calendar: {
      executor: 'constant-arrival-rate',
      exec: 'calendar',
      rate: 1,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
  },
  // 行程 API 是這次要守住的：模型怎麼壞都要 100% 成功、而且不被拖慢
  thresholds: {
    outage_calendar_failed: ['count==0'],
    outage_calendar: ['p(95)<1000'],
  },
};

export function setup() {
  return signIn();
}

export function assistant(session) {
  useSession(session);
  const res = http.post(ASSISTANT_URL, JSON.stringify({
    message: MESSAGES[Math.floor(Math.random() * MESSAGES.length)],
  }), jsonParams({ responseCallback: http.expectedStatuses(200, 502, 503) }));

  let outcome = 'other';
  if (res.status === 200) {
    outcome = 'ok';
  } else if (res.status === 502 || res.status === 503) {
    outcome = code(res) in OUTCOMES ? code(res) : 'other';
  }
  assistantMs[outcome].add(res.timings.duration);
  assistantCount[outcome].add(1);
  console.log(`${now()} assistant status=${res.status} code=${outcome} ${Math.round(res.timings.duration)}ms`
      + (res.headers['Retry-After'] ? ` retry-after=${res.headers['Retry-After']}` : ''));
}

export function calendar(session) {
  useSession(session);
  const res = http.get(`${BASE_URL}/api/calendar/events?from=2026-10-01&to=2026-11-01`);
  calendarMs.add(res.timings.duration);
  calendarCount.add(1);
  if (res.status !== 200) {
    calendarFailed.add(1);
    console.log(`${now()} calendar status=${res.status} ${Math.round(res.timings.duration)}ms`);
  }
}

// 印出來的每一行都帶時間（本機時區），才對得上「幾點停掉模型」
function now() {
  return new Date().toTimeString().slice(0, 8);
}

function code(res) {
  try {
    return res.json('code') || '';
  } catch (e) {
    return '';
  }
}

export function handleSummary(data) {
  const m = (name) => data.metrics[name] && data.metrics[name].values;
  const ms = (v) => `${Math.round(v)}ms`.padStart(9);
  const rows = Object.entries(OUTCOMES).map(([key, label]) => {
    const n = (m(`outage_assistant_${key}_count`) || { count: 0 }).count;
    const v = m(`outage_assistant_${key}`);
    if (!n) return `  ${'0'.padStart(5)}  ${''.padStart(9)}  ${''.padStart(9)}  ${label}`;
    return `  ${String(n).padStart(5)}  ${ms(v.med)}  ${ms(v.max)}  ${label}`;
  });
  const cal = m('outage_calendar') || { 'p(95)': 0, max: 0 };
  const calCount = (m('outage_calendar_count') || { count: 0 }).count;
  const calFailed = (m('outage_calendar_failed') || { count: 0 }).count;
  const dropped = (m('dropped_iterations') || { count: 0 }).count;
  return {
    stdout: `\n助理（每分鐘 ${RATE_PER_MIN} 人，共 ${DURATION_S} 秒）\n`
        + `  ${'次數'.padStart(4)}  ${'中位數'.padStart(7)}  ${'最久'.padStart(8)}  結果\n${rows.join('\n')}\n`
        + `\n行程 API（每秒 1 人）：${calCount} 次，失敗 ${calFailed} 次，p95 ${Math.round(cal['p(95)'])}ms，最久 ${Math.round(cal.max)}ms\n`
        + `k6 沒送出去（dropped_iterations）：${dropped}\n`,
  };
}
