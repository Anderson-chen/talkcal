// deploy：邊壓邊部署，量部署那幾十秒裡有幾個使用者受影響、怎麼受影響。
//
// 用 constant-arrival-rate，每分鐘固定來 RATE_PER_MIN 個人、各說一句，失敗數 ≈ 部署期間進來的人數，
// 可以直接拿來比部署流程改之前和改之後。用 knee.js 那種固定 VU、說完立刻再說的寫法會量錯：
// app 停掉時每個請求幾毫秒就失敗、VU 立刻再送，十幾秒能刷出九萬個失敗，只反映 k6 重送得多快。
//
// 失敗分三種，各記一個 counter（收工印成表，也送進 Grafana）：
//   deploy_fast_fail：不到 1 秒、沒拿到回應。app 已經不收新請求，或新的還沒啟動
//   deploy_slow_fail：超過 1 秒、沒拿到回應，連線被中斷。可能是處理到一半容器被 SIGKILL，
//                     也可能是在連線佇列裡沒被處理就斷了。client 端分不出來，要對照舊容器的結束碼（137 = SIGKILL）和 app 的 log
//   deploy_error：拿到回應但不是 200（例如 502）
//
// 執行方式（先啟動 ops/ 才看得到圖）：
//   1. perf/run-to-grafana.sh perf/deploy.js
//   2. 大約 60 秒後，在另一個終端機：cd backend && ./gradlew deployApp -x test
//   3. 看收工的表和 Grafana「talkcal — 壓測（k6）」上的部署線
//
// 預設送到 docker 那台 app（18090），部署的也是這台。

import './lib/docker-target.js';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { ASSISTANT_URL, MESSAGES, jsonParams, signIn, useSession } from './lib/assistant.js';

// 要低於容量，量的才是部署的影響。每分鐘 60 人時，模型同時處理到 20 幾個、KV cache 占滿、回 502，
// 跟部署無關的失敗混進來就分不出來；20 人時模型同時處理維持在個位數
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
      // 一個請求占住一個 VU 直到回來，模型排隊時要幾十秒。VU 不夠的話 k6 跳過那幾輪，部署期間的人數就少算
      preAllocatedVUs: 50,
      maxVUs: 300,
      // 最後進來的人也要等到回覆或失敗，才算得進表裡
      gracefulStop: '150s',
    },
  },
};

// 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
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
    // 1 秒當分界：app 不在時幾毫秒就失敗，處理到一半被中斷的至少已經執行了一段時間
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
  // 數字放前面：中文字在終端機裡佔兩格，padStart 只算字數，標籤放前面對不齊
  const lines = rows.map(([label, n]) => `  ${String(n).padStart(5)}  ${label}`);
  return { stdout: `\n部署期間的請求（每分鐘 ${RATE_PER_MIN} 人，共 ${DURATION_S} 秒）\n${lines.join('\n')}\n` };
}
