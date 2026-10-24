// canary —— 邊壓邊換 app（#20）：零停機部署有沒有讓任何一個請求失敗、壞掉的新版 canary 影響了多少請求。
//
// 跟 deploy.js 一樣用 constant-arrival-rate（每秒固定來 RATE_PER_S 個請求，app 掛了也不會因此多送），
// 失敗數 = 受影響的請求數，可以直接拿來比。差別有兩個：
//   1. 打 nginx（web），不打 app 的埠：流量切到哪個 slot 是 nginx 決定的，繞過它就量不到切換
//   2. 打查行程（GET /api/calendar/events），不打 AI 助理：不叫模型，每秒幾十個也不會塞爆，
//      也不受 nginx 對助理的限流（每分鐘 30 次）影響。要量的是切換本身，不是模型的容量
//
// 失敗分三種（收工印一張表）：
//   canary_conn_fail —— 沒拿到任何回應（status 0）：連不上 app，或連線被砍。零停機部署要是 0
//   canary_5xx       —— 拿到 5xx：壞掉的新版、或 nginx 找不到 app（502）
//   canary_other     —— 其他非 200（例如 401：session 換到另一個 app 就不認得了）
//
// 怎麼跑見 perf/README.md「零停機部署與 canary」。要畫時間軸就加 --out csv=...，
// 每個請求的時間和 status 都在裡面（perf/canary/timeline.py 把它整理成每 10 秒一列）。

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, signIn, useSession } from './lib/assistant.js';

const RATE_PER_S = Number(__ENV.RATE_PER_S || 20);
const DURATION_S = Number(__ENV.DURATION_S || 180);
const EVENTS_URL = `${BASE_URL}/api/calendar/events?from=2030-01-01&to=2030-02-01`;

const ok = new Counter('canary_ok');
const connFail = new Counter('canary_conn_fail');
const serverError = new Counter('canary_5xx');
const other = new Counter('canary_other');

export const options = {
  scenarios: {
    canary: {
      executor: 'constant-arrival-rate',
      rate: RATE_PER_S,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
};

export function setup() {
  return signIn();
}

export default function (session) {
  useSession(session);
  // 失敗是這支要數的東西，不讓 k6 把它們算進 http_req_failed 再印一堆警告
  const res = http.get(EVENTS_URL, { timeout: '30s', responseCallback: http.expectedStatuses(200) });
  if (res.status === 200) ok.add(1);
  else if (res.status === 0) connFail.add(1);
  else if (res.status >= 500) serverError.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const total = ['canary_ok', 'canary_conn_fail', 'canary_5xx', 'canary_other'].map(count).reduce((a, b) => a + b, 0);
  const rows = [
    ['成功（200）', count('canary_ok')],
    ['沒拿到回應（連不上、連線被砍）', count('canary_conn_fail')],
    ['5xx', count('canary_5xx')],
    ['其他非 200', count('canary_other')],
    ['k6 沒送出去（dropped_iterations）', count('dropped_iterations')],
  ];
  const lines = rows.map(([label, n]) => `  ${String(n).padStart(6)}  ${label}`);
  return {
    stdout: `\n切換期間的請求（每秒 ${RATE_PER_S} 個，共 ${DURATION_S} 秒，${total} 個）\n${lines.join('\n')}\n`,
  };
}
