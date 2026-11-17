// outage：模型斷線或卡住時，助理是不是快速失敗、行程 API 是不是照常。
//
// 同時有兩群人，都用 constant-arrival-rate，模型壞了也不會多來人，前後的數字才比得起來：
//   assistant：每分鐘 RATE_PER_MIN 個人跟助理說一句
//   calendar：每秒 1 個人讀一個月的行程，不經過模型
//
// 給了 FAULT，k6 會在第 FAULT_AT_S 秒透過 Toxiproxy 的 API 讓 PROXY 那一段故障、第 RECOVER_AT_S 秒恢復，每次時間點一樣才能重現。
// deploy/ 要疊上 compose.chaos.yaml 啟動，app 才經過 Toxiproxy：
//   FAULT=down  停用那段 proxy，幾毫秒就失敗
//   FAULT=hang  回應晚 10 分鐘才到，比呼叫模型的逾時還久，要等到逾時才失敗
//   FAULT=slow  每個回應慢 SLOW_MS（預設 40 秒），會回，但超過 30 秒的慢呼叫門檻
// 不給 FAULT 就不碰 Toxiproxy，由人在另一個終端機製造故障（見 deploy/README.md 的「故障演練」）。
// 每一句的結果、故障和恢復的那一刻都各印一行，帶時間。
//
// 預期的結果如下，門檻在 application.properties 的 resilience4j.*（chat-model）：
//   - 前 5 次失敗讓熔斷打開，之後助理幾毫秒就回 503 MODEL_UNAVAILABLE
//   - 卡住時同時在等的最多 50 個（bulkhead），再多就回 503 MODEL_BUSY
//   - 行程 API 從頭到尾都是 200
//   - 模型恢復後 30 秒內熔斷試探、關回來，助理又回 200
//
// 預設送到 Docker 裡的 app（18090），Toxiproxy 的 API 用 TOXIPROXY_URL（預設 localhost:8474）：
//   FAULT=down k6 run perf/outage.js
//   FAULT=hang DURATION_S=360 RECOVER_AT_S=220 k6 run perf/outage.js   # 卡住要等逾時，拉長一點
//   PROXY=postgres FAULT=down k6 run perf/outage.js                     # 讓資料庫那一段故障

import './lib/docker-target.js';
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { ASSISTANT_URL, MESSAGES, field, jsonParams, listMonth, signIn, useSession } from './lib/assistant.js';

const RATE_PER_MIN = Number(__ENV.RATE_PER_MIN || 20); // 要低於容量，量到的失敗才都是故障造成的
const DURATION_S = Number(__ENV.DURATION_S || 300);

// ── 故障注入（Toxiproxy）──
const FAULT = __ENV.FAULT || '';
const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://localhost:8474';
const PROXY = __ENV.PROXY || 'llm'; // deploy/toxiproxy.json 裡的名字：llm 或 postgres
const FAULT_AT_S = Number(__ENV.FAULT_AT_S || 60); // 前一分鐘當正常的對照
const RECOVER_AT_S = Number(__ENV.RECOVER_AT_S || 180);
const SLOW_MS = Number(__ENV.SLOW_MS || 40000);
const HANG_MS = 600000; // 比呼叫模型的逾時（2 分鐘）久，對 app 來說就是永遠不回
const TOXIC = 'outage'; // 恢復時照名字拿掉

const FAULTS = {
  down: {
    inject: () => setEnabled(false),
    recover: () => setEnabled(true),
  },
  hang: {
    // 不能用 Toxiproxy 的 timeout toxic，這種 toxic 擋不住連線被關閉：加在 downstream，模型回完就關；
    // 加在 upstream，請求到不了模型，llama-server 對閒置 5 秒的連線也會關。兩種都會把關閉傳回 app，
    // app 幾秒就收到連線中斷，變成斷線。latency toxic 把回應連同關閉一起延後，app 才是真的一直等
    inject: () => addToxic('latency', { latency: HANG_MS }, 'downstream'),
    recover: removeToxic,
  },
  slow: {
    // 加在 downstream：模型照常收到、照常回，回應晚 SLOW_MS 才到 app
    inject: () => addToxic('latency', { latency: SLOW_MS }, 'downstream'),
    recover: removeToxic,
  },
};
if (FAULT && !FAULTS[FAULT]) {
  throw new Error(`FAULT 只能是 ${Object.keys(FAULTS).join('、')}，收到 ${FAULT}`);
}
// 時間點錯了的話，可能根本沒造成故障，結果是一張全 200 的表，看起來像熔斷有效
if (FAULT && !(0 <= FAULT_AT_S && FAULT_AT_S < RECOVER_AT_S && RECOVER_AT_S < DURATION_S)) {
  throw new Error(`要 0 ≤ FAULT_AT_S < RECOVER_AT_S < DURATION_S，收到 ${FAULT_AT_S}、${RECOVER_AT_S}、${DURATION_S}`);
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

const OUTCOMES = {
  ok: '200 正常回覆',
  ASSISTANT_FAILED: '502 ASSISTANT_FAILED（叫了、失敗）',
  MODEL_UNAVAILABLE: '503 MODEL_UNAVAILABLE（熔斷，沒叫模型）',
  MODEL_BUSY: '503 MODEL_BUSY（滿載，沒叫模型）',
  other: '其他（含沒拿到回應）',
};
// 次數從 Trend 的 count 拿，不另外數
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
      // 模型卡住時一個請求占住一個 VU 2 分鐘，VU 不夠 k6 就會跳過那幾輪
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
  // p(95) 給行程 API 的門檻，其他給結束時印的表
  summaryTrendStats: ['count', 'med', 'p(95)', 'max'],
  // 模型怎麼壞，行程 API 都要全部成功、不被拖慢
  thresholds: {
    outage_calendar_failed: ['count==0'],
    outage_calendar: ['p(95)<1000'],
  },
};

export function setup() {
  if (FAULT) {
    // 上一次中途被中斷的話 toxic 可能還留著，先把所有 proxy 接回、所有 toxic 拿掉
    const res = http.post(`${TOXIPROXY_URL}/reset`);
    if (res.status !== 204) {
      throw new Error(`連不到 Toxiproxy（${TOXIPROXY_URL}）：${res.status}。deploy/ 要疊 compose.chaos.yaml 起`);
    }
  }
  return signIn();
}

// 正常結束、Ctrl+C、中途出錯都會執行 teardown。toxic 留著的話，本機的 app 之後每個請求都會失敗
export function teardown() {
  if (FAULT) {
    http.post(`${TOXIPROXY_URL}/reset`);
  }
}

export function fault() {
  sleep(FAULT_AT_S);
  toxiproxy(`弄壞 ${PROXY}（${FAULT}）`, FAULTS[FAULT].inject());
  sleep(RECOVER_AT_S - FAULT_AT_S);
  toxiproxy(`恢復 ${PROXY}`, FAULTS[FAULT].recover());
}

// Toxiproxy 的 API 呼叫失敗就讓演練失敗，不然要等到結束才發現根本沒造成故障
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

// 本機時區，跟 Grafana、app 的 log 對時間用
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
