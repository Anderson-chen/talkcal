// deploy —— 邊壓邊部署：部署的那幾十秒裡，有幾個使用者受影響、怎麼受影響。
//
// 跟 knee.js 的差別：knee 是「固定 N 個人、說完立刻再說」。拿它來壓部署會量錯——
// app 停掉的那十幾秒，每個請求幾毫秒就失敗，VU 立刻再送，十幾秒刷出九萬個失敗（2026-10-12 第一次重現時踩到），
// 失敗數只反映 k6 重送得多快，不代表有多少人受影響。
// 這支用 constant-arrival-rate：每分鐘固定「來」RATE_PER_MIN 個人、各說一句，app 掛了也不會因此多來人，
// 所以失敗數 ≈ 部署期間進來的人數，可以直接拿來比「改之前 / 改之後」。
//
// 失敗分三種，各記一個 counter（收工印一張表，也會送進 Grafana）：
//   deploy_fast_fail —— 一送就失敗（< 1 秒、沒拿到任何回應）：app 已經不收新請求了，或新的還沒起來
//   deploy_slow_fail —— 等了一陣子才失敗（≥ 1 秒、沒拿到任何回應）：連線被砍。
//                       可能是處理到一半、容器被 SIGKILL；也可能是卡在連線佇列裡沒被處理就被斷掉。
//                       光看 client 端分不出這兩種，要對照舊容器的結束碼（137 = 被 SIGKILL）和 app 的 log
//   deploy_error     —— 拿到回應但不是 200（例如 502）
//
// 怎麼跑（先起 ops/ 才看得到圖）：
//   1. perf/run-to-grafana.sh perf/deploy.js
//   2. 大約 60 秒後，在另一個終端機：cd backend && ./gradlew deployApp -x test
//   3. 等它跑完，看收工的表和 Grafana「talkcal — 壓測（k6）」上的紅線、綠線
//
// 預設打 docker 那台 app（18090）：部署的就是它。

import './lib/docker-target.js';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { ASSISTANT_URL, MESSAGES, jsonParams, signIn, useSession } from './lib/assistant.js';

// 每分鐘來幾個人。要低於容量：量的是部署的影響，不是過載。
// 2026-10-12 用每秒 1 人（60）跑，問題隨機混合時模型同時處理到 20 幾個，KV cache 塞爆、回 502，
// 跟部署無關的失敗混進來，就分不出哪些是部署造成的。20 人時模型同時處理維持在個位數
const RATE_PER_MIN = Number(__ENV.RATE_PER_MIN || 20);
const DURATION_S = Number(__ENV.DURATION_S || 180); // 要涵蓋部署前的穩定期、部署、部署後恢復

const fastFail = new Counter('deploy_fast_fail');
const slowFail = new Counter('deploy_slow_fail');
const errorStatus = new Counter('deploy_error');
const ok = new Counter('deploy_ok');

export const options = {
  scenarios: {
    deploy: {
      executor: 'constant-arrival-rate',
      rate: RATE_PER_MIN,
      timeUnit: '1m',
      duration: `${DURATION_S}s`,
      // 一個人等多久就占住一個 VU 多久。模型塞車時一個請求可能幾十秒，備足 VU，
      // 否則 k6 會跳過那幾輪（dropped_iterations），部署期間的人數就少算了
      preAllocatedVUs: 50,
      maxVUs: 300,
      // 最後進來的人也要等到回覆或失敗，才算得進表裡
      gracefulStop: '150s',
    },
  },
};

// 登入一次，所有 VU 共用（理由見 lib/assistant.js 的 signIn）
export function setup() {
  return signIn();
}

export default function (session) {
  useSession(session);
  const res = http.post(ASSISTANT_URL, JSON.stringify({
    message: MESSAGES[Math.floor(Math.random() * MESSAGES.length)],
  }), jsonParams());

  if (res.status === 200) {
    ok.add(1);
  } else if (res.status === 0) {
    // 1 秒當分界：app 不在時幾毫秒就失敗；處理中被砍的，至少已經跑了一段
    (res.timings.duration < 1000 ? fastFail : slowFail).add(1);
  } else {
    errorStatus.add(1);
  }
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const rows = [
    ['成功（200）', count('deploy_ok')],
    ['一送就失敗（app 不收或還沒起來）', count('deploy_fast_fail')],
    ['等了一陣子才失敗（連線被砍）', count('deploy_slow_fail')],
    ['拿到錯誤碼（非 200）', count('deploy_error')],
    ['k6 沒送出去（dropped_iterations）', count('dropped_iterations')],
  ];
  // 數字放前面：中文字和英文字在終端機裡寬度不同，標籤放前面怎麼補空白都對不齊
  const lines = rows.map(([label, n]) => `  ${String(n).padStart(5)}  ${label}`);
  return { stdout: `\n部署期間的請求（每分鐘 ${RATE_PER_MIN} 人，共 ${DURATION_S} 秒）\n${lines.join('\n')}\n` };
}
