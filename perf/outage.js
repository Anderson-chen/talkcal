// outage —— 模型掛掉演練（#44）：模型斷線或卡住的時候，助理是不是快速失敗、行程 API 是不是照常可用。
//
// 同時跑兩群人：
//   assistant —— 每分鐘 RATE_PER_MIN 個人跟助理說一句（跟 deploy.js 一樣用 constant-arrival-rate：
//                模型掛了也不會因此多來人，數字才能直接比）
//   calendar  —— 每秒 1 個人讀一個月的行程（GET /api/calendar/events），不經過模型
//
// 故障由 k6 自己按時製造：給了 FAULT，就在第 FAULT_AT_S 秒透過 Toxiproxy 的 API 弄壞 app 跟模型之間那一段，
// 第 RECOVER_AT_S 秒恢復（deploy/ 要疊 compose.chaos.yaml 起，app 才經過 Toxiproxy）。每一次都同樣的時間點，結果才能重現：
//   FAULT=down  斷線：停用那段 proxy                → 連不上，幾毫秒就失敗
//   FAULT=hang  卡住：連得上、永遠不回（timeout 毒）  → 要等到叫模型的逾時才失敗
//   FAULT=slow  很慢：每個回應慢 SLOW_MS 毫秒（latency 毒，預設 40 秒）→ 會回，但超過 30 秒的慢呼叫門檻
// 不給 FAULT 就不碰 Toxiproxy，由人在另一個終端機弄壞（toxiproxy-cli，見 deploy/README.md 的「故障演練」）。
// 每一句的結果都印一行（帶時間），弄壞、恢復的那一刻也各印一行，對著看。
//
// 收工印一張表：助理的每種結果（200、502、503 的兩種 code）各幾個、各花多久；行程 API 成功幾個、p95 多久。
// 預期（門檻、理由在 application.properties 的 resilience4j.*（chat-model））：
//   - 模型掛掉後，前 5 次失敗讓熔斷打開；之後助理一律回 503 MODEL_UNAVAILABLE，幾毫秒
//   - 卡住的時候還在等的請求最多 50 個（bulkhead），再多就回 503 MODEL_BUSY
//   - 行程 API 從頭到尾都是 200
//   - 模型恢復後 30 秒內熔斷試探、關回來，助理又回 200
//
// 怎麼跑（打哪台用 BASE_URL，預設是 docker 那台 18090；Toxiproxy 的 API 用 TOXIPROXY_URL，預設 localhost:8474）：
//   FAULT=down k6 run perf/outage.js
//   FAULT=hang DURATION_S=360 RECOVER_AT_S=220 k6 run perf/outage.js   # 卡住要等逾時，拉長一點
//   PROXY=postgres FAULT=down k6 run perf/outage.js                     # 換弄壞資料庫那一段

import './lib/docker-target.js';
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { ASSISTANT_URL, MESSAGES, field, jsonParams, listMonth, signIn, useSession } from './lib/assistant.js';

const RATE_PER_MIN = Number(__ENV.RATE_PER_MIN || 20); // 要低於容量，理由同 deploy.js
const DURATION_S = Number(__ENV.DURATION_S || 300);

// ── 故障注入（Toxiproxy）──
const FAULT = __ENV.FAULT || '';
const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://localhost:8474';
const PROXY = __ENV.PROXY || 'llm'; // deploy/toxiproxy.json 裡的名字：llm 或 postgres
const FAULT_AT_S = Number(__ENV.FAULT_AT_S || 60); // 前一分鐘是正常的，當對照
const RECOVER_AT_S = Number(__ENV.RECOVER_AT_S || 180);
const SLOW_MS = Number(__ENV.SLOW_MS || 40000);
const TOXIC = 'outage'; // 這支腳本加的毒都叫這個名字，恢復時照名字拿掉

// 每種故障：怎麼弄壞、怎麼恢復。都只動 PROXY 那一段，另一段照常
const FAULTS = {
  down: {
    inject: () => setEnabled(false),
    recover: () => setEnabled(true),
  },
  hang: {
    // timeout 0：資料一律不轉、連線也不關，就是「連得上、永遠不回」。
    // 要加在 upstream（app → 模型），請求根本到不了模型：加在 downstream 的話，請求照樣送到、模型算完回應就關連線，
    // Toxiproxy 跟著把 app 這端也關掉，app 幾秒就收到「連線中斷」—— 那是斷線，不是卡住（2026-10-29 實際踩到）
    inject: () => addToxic('timeout', { timeout: 0 }, 'upstream'),
    recover: removeToxic,
  },
  slow: {
    // 加在 downstream：請求照常送到、模型照常回，只是回應晚 SLOW_MS 才到 app
    inject: () => addToxic('latency', { latency: SLOW_MS }, 'downstream'),
    recover: removeToxic,
  },
};
if (FAULT && !FAULTS[FAULT]) {
  throw new Error(`FAULT 只能是 ${Object.keys(FAULTS).join('、')}，收到 ${FAULT}`);
}

// Toxiproxy 的 API：https://github.com/Shopify/toxiproxy#http-api
function setEnabled(enabled) {
  return http.post(`${TOXIPROXY_URL}/proxies/${PROXY}`, JSON.stringify({ enabled }));
}

function addToxic(type, attributes, stream) {
  return http.post(`${TOXIPROXY_URL}/proxies/${PROXY}/toxics`,
      JSON.stringify({ name: TOXIC, type, stream, attributes }));
}

function removeToxic() {
  return http.del(`${TOXIPROXY_URL}/proxies/${PROXY}/toxics/${TOXIC}`);
}

// 助理的結果依 code 分開記：200、502（這次失敗）、503 的兩種（沒叫模型）、其他（含連線失敗）
const OUTCOMES = {
  ok: '200 正常回覆',
  ASSISTANT_FAILED: '502 ASSISTANT_FAILED（叫了、失敗）',
  MODEL_UNAVAILABLE: '503 MODEL_UNAVAILABLE（熔斷，沒叫模型）',
  MODEL_BUSY: '503 MODEL_BUSY（滿載，沒叫模型）',
  other: '其他（含沒拿到回應）',
};
// 次數也從 Trend 拿（options 的 summaryTrendStats 有 count），不另外數
const assistantMs = Object.fromEntries(Object.keys(OUTCOMES).map((k) => [k, new Trend(`outage_assistant_${k}`, true)]));
const calendarMs = new Trend('outage_calendar', true);
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
    // 給了 FAULT 才有：一個 VU 跑一次，按時弄壞、按時恢復
    ...(FAULT ? {
      fault: {
        executor: 'per-vu-iterations',
        exec: 'fault',
        vus: 1,
        iterations: 1,
        maxDuration: `${DURATION_S}s`,
      },
    } : {}),
  },
  // 收工的表要次數、中位數、最久；p(95) 給行程 API 的門檻
  summaryTrendStats: ['count', 'med', 'p(95)', 'max'],
  // 行程 API 是這次要守住的：模型怎麼壞都要 100% 成功、而且不被拖慢
  thresholds: {
    outage_calendar_failed: ['count==0'],
    outage_calendar: ['p(95)<1000'],
  },
};

export function setup() {
  if (FAULT) {
    // 上一次演練中途被中斷的話，毒可能還留著：先全部清掉（所有 proxy 接回、拿掉所有毒），從正常開始
    const res = http.post(`${TOXIPROXY_URL}/reset`);
    if (res.status !== 204) {
      throw new Error(`連不到 Toxiproxy（${TOXIPROXY_URL}）：${res.status}。deploy/ 要疊 compose.chaos.yaml 起`);
    }
  }
  return signIn();
}

export function fault() {
  sleep(FAULT_AT_S);
  toxiproxy(`弄壞 ${PROXY}（${FAULT}）`, FAULTS[FAULT].inject());
  sleep(RECOVER_AT_S - FAULT_AT_S);
  toxiproxy(`恢復 ${PROXY}`, FAULTS[FAULT].recover());
}

// 弄壞、恢復的那一刻各印一行，跟每一句的結果排在一起看；Toxiproxy 沒照做就讓演練失敗，不要跑完才發現根本沒弄壞
function toxiproxy(what, res) {
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`${what} 失敗：${res.status} ${res.body}`);
  }
  console.log(`${now()} ===== ${what} =====`);
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
    const code = field(res, 'code');
    outcome = code in OUTCOMES ? code : 'other';
  }
  assistantMs[outcome].add(res.timings.duration);
  console.log(`${now()} assistant status=${res.status} code=${outcome} ${Math.round(res.timings.duration)}ms`
      + (res.headers['Retry-After'] ? ` retry-after=${res.headers['Retry-After']}` : ''));
}

export function calendar(session) {
  useSession(session);
  const res = listMonth();
  calendarMs.add(res.timings.duration);
  if (res.status !== 200) {
    calendarFailed.add(1);
    console.log(`${now()} calendar status=${res.status} ${Math.round(res.timings.duration)}ms`);
  }
}

// 印出來的每一行都帶時間（本機時區），才對得上「幾點停掉模型」
function now() {
  return new Date().toTimeString().slice(0, 8);
}

export function handleSummary(data) {
  const m = (name) => data.metrics[name] && data.metrics[name].values;
  const ms = (v) => `${Math.round(v)}ms`.padStart(9);
  const rows = Object.entries(OUTCOMES).map(([key, label]) => {
    const v = m(`outage_assistant_${key}`);
    const n = v ? v.count : 0;
    if (!n) return `  ${'0'.padStart(5)}  ${''.padStart(9)}  ${''.padStart(9)}  ${label}`;
    return `  ${String(n).padStart(5)}  ${ms(v.med)}  ${ms(v.max)}  ${label}`;
  });
  const cal = m('outage_calendar') || { count: 0, 'p(95)': 0, max: 0 };
  const calFailed = (m('outage_calendar_failed') || { count: 0 }).count;
  const dropped = (m('dropped_iterations') || { count: 0 }).count;
  return {
    stdout: `\n助理（每分鐘 ${RATE_PER_MIN} 人，共 ${DURATION_S} 秒`
        + (FAULT ? `；第 ${FAULT_AT_S} 秒弄壞 ${PROXY}（${FAULT}）、第 ${RECOVER_AT_S} 秒恢復` : '') + '）\n'
        + `  ${'次數'.padStart(4)}  ${'中位數'.padStart(7)}  ${'最久'.padStart(8)}  結果\n${rows.join('\n')}\n`
        + `\n行程 API（每秒 1 人）：${cal.count} 次，失敗 ${calFailed} 次，p95 ${Math.round(cal['p(95)'])}ms，最久 ${Math.round(cal.max)}ms\n`
        + `k6 沒送出去（dropped_iterations）：${dropped}\n`,
  };
}
