// slo —— 一直有人在用的「平常流量」：行程 CRUD 為主，偶爾跟助理說一句。給 SLO（docs/slo.md）用的。
//
// 兩個用途：
//   1. 量一般 API 的延遲基準（docs/slo.md 的門檻從這裡來）：收工印行程 API 與助理各自的 p95、p99、5xx 比例。
//   2. 驗 burn-rate 告警：開著這支當背景流量，再讓依賴掛掉（停資料庫、模型位址指錯），
//      看 SLO 儀表板的 burn rate 往上衝、告警進 firing；依賴恢復、只剩這支的時候告警不該響（反例）。
//
// 跟 knee.js 的差別：knee 量「持續 N 個人在用」到哪裡會塞，這支用 constant-arrival-rate 照固定節奏送，
// 不管 server 回得快不快——SLO 看的是「使用者來了，有幾成被好好服務」，到達速率不該跟著 server 變慢而降。
//
// 預設打 docker 那台 app（18090），跑 DURATION（預設 2m）：
//   k6 run perf/slo.js
// 參數（環境變數）：
//   CRUD_RPS        行程 API 每秒幾個請求（預設 10，GET 列表 : 新增+刪除 ≈ 8 : 2）
//   ASSIST_PER_MIN  每分鐘跟助理說幾句（預設 6；0 = 不碰模型）
//   DURATION        跑多久（預設 2m）

import './lib/docker-target.js';
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, MESSAGES, jsonParams, say, signIn, useSession } from './lib/assistant.js';

const CRUD_RPS = Number(__ENV.CRUD_RPS || 10);
const ASSIST_PER_MIN = Number(__ENV.ASSIST_PER_MIN || 6);
const DURATION = __ENV.DURATION || '2m';

const scenarios = {
  crud: {
    executor: 'constant-arrival-rate',
    exec: 'crud',
    tags: { api: 'crud' },
    rate: CRUD_RPS,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(5, CRUD_RPS * 2),
    maxVUs: Math.max(20, CRUD_RPS * 10),
  },
};
if (ASSIST_PER_MIN > 0) {
  scenarios.assistant = {
    executor: 'constant-arrival-rate',
    exec: 'assistant',
    tags: { api: 'assistant' },
    rate: ASSIST_PER_MIN,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 5,
    maxVUs: 50,
  };
}

// 不拿門檻判成敗（造錯的時候本來就該失敗），只是讓收工報表分開印行程 API 和助理（k6 只印有門檻的子指標）。
// api tag 設在 scenario 上，那個 scenario 發的請求都會帶；setup() 的登入不在 scenario 裡，不會被算進去。
export const options = {
  scenarios,
  thresholds: {
    'http_req_duration{api:crud}': ['max>=0'],
    'http_req_duration{api:assistant}': ['max>=0'],
    'http_req_failed{api:crud}': ['rate>=0'],
    'http_req_failed{api:assistant}': ['rate>=0'],
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return signIn();
}

// 月曆一頁：前端打開月曆就是這個請求，最常見
function listMonth() {
  return http.get(`${BASE_URL}/api/calendar/events?from=2026-10-01&to=2026-11-01`);
}

export function crud(session) {
  useSession(session);
  // 十次裡兩次「新增一筆再刪掉」：寫入路徑也算進來，又不讓測試帳號的行程越積越多
  if (Math.random() < 0.2) {
    const body = { events: [{ title: 'slo 壓測', start: '2026-10-20T09:00:00', end: '2026-10-20T10:00:00', category: 'WORK' }] };
    const created = http.post(`${BASE_URL}/api/calendar/events`, JSON.stringify(body), jsonParams());
    check(created, { '新增回 201': (r) => r.status === 201 });
    if (created.status === 201) {
      const id = created.json('events.0.id');
      http.del(`${BASE_URL}/api/calendar/events/${id}`, null, jsonParams());
    }
    return;
  }
  check(listMonth(), { '列表回 200': (r) => r.status === 200 });
}

export function assistant(session) {
  useSession(session);
  say(MESSAGES[Math.floor(Math.random() * MESSAGES.length)]);
}
