// rollout —— 邊打邊換版（k8s rolling update），證明換版期間沒有空窗（#56）。
//
// 跟 deploy.js 的差別：deploy.js 問 AI 助理，量的是「處理中的長請求會不會被砍」（graceful shutdown）；
// 這支打一個很便宜的 API，每秒很多次，量的是「有沒有哪一刻沒人接」—— 空窗只要一兩秒，每秒 20 次就一定撞得到。
// 打 GET /api/auth/providers：不用登入、不叫模型，但會經過 web 的 nginx 再到 app，兩層的換版都量得到。
//
// 怎麼跑（叢集用 deploy/k8s/overlays/kind/up.sh 起）：
//   1. k6 run perf/rollout.js
//   2. 大約 10 秒後，在另一個終端機：kubectl -n talkcal rollout restart deployment/app
//   3. 收工的表：失敗數要是 0。反例（拿掉 readinessProbe、改成 Recreate）的跑法見 deploy/k8s/README.md
//
// 預設打 kind 的 web（38080）。BASE_URL=http://127.0.0.1:38090 直接打 app，不經過 nginx

import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:38080';
const RATE_PER_S = Number(__ENV.RATE_PER_S || 20);
const DURATION_S = Number(__ENV.DURATION_S || 120);  // 要涵蓋換版前的穩定期、整個 rollout、換完以後

const ok = new Counter('rollout_ok');
const fastFail = new Counter('rollout_fast_fail');
const slowFail = new Counter('rollout_slow_fail');
const errorStatus = new Counter('rollout_error');

export const options = {
  scenarios: {
    rollout: {
      // 固定每秒來幾個請求，app 掛了也不會因為失敗得快而多送（理由同 deploy.js）
      executor: 'constant-arrival-rate',
      rate: RATE_PER_S,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      // 每個請求幾毫秒就回來，VU 數跟速率一樣就夠；備太少的話 k6 會跳過幾輪（dropped_iterations），看起來像少打了
      preAllocatedVUs: Math.max(20, RATE_PER_S),
      maxVUs: Math.max(200, RATE_PER_S * 4),
    },
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/auth/providers`, { timeout: '10s' });
  if (res.status === 200) {
    ok.add(1);
    return;
  }
  // 失敗的當下印出時間：對照 kubectl get pods -w，看得出是換版的哪一步出事
  console.warn(`${new Date().toISOString()} status=${res.status} ${res.error || ''} ${Math.round(res.timings.duration)}ms`);
  if (res.status === 0) {
    (res.timings.duration < 1000 ? fastFail : slowFail).add(1);
  } else {
    errorStatus.add(1);
  }
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const rows = [
    ['成功（200）', count('rollout_ok')],
    ['一送就失敗（沒人接）', count('rollout_fast_fail')],
    ['等了一陣子才失敗（連線被砍）', count('rollout_slow_fail')],
    ['拿到錯誤碼（例如 nginx 的 502）', count('rollout_error')],
    ['k6 沒送出去（dropped_iterations）', count('dropped_iterations')],
  ];
  const lines = rows.map(([label, n]) => `  ${String(n).padStart(5)}  ${label}`);
  return { stdout: `\n換版期間的請求（${BASE_URL}，每秒 ${RATE_PER_S} 次，共 ${DURATION_S} 秒）\n${lines.join('\n')}\n` };
}
