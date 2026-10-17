// tokens —— 量「AI 助理一個請求平均燒掉多少 token」，成本分析（docs/cost.md）的輸入就從這裡來（#35）。
//
// 不是壓力測試：1 個虛擬使用者，訊息池（lib/assistant.js 的 MESSAGES，查詢和提議都有）照順序每句說 ROUNDS 輪。
// 一句一句來，不併發：要量的是「一個請求的份量」，併發只會讓 llama.cpp 排隊，token 數不會變。
//
// token 數不是 k6 自己數的：app 每叫一次模型，Spring AI 就把回應裡的 usage 記進 gen_ai_client_token_usage_total。
// 這支在開始前、結束後各讀一次 app 的 /actuator/prometheus，相減就是這次跑的用量 ——
// 跟 Grafana 成本 dashboard 用的是同一個指標，兩邊的數字對得起來。
//
// 怎麼跑（app 要先起來；預設打本機 bootRun 的 8090）：
//   k6 run perf/tokens.js
//   PowerShell:  $env:ROUNDS="3"; k6 run perf/tokens.js
// 換算費用用的單價（USD / 每 1M token）可以覆蓋，預設是 Bedrock 東京 Qwen3 32B 的 Standard（來源見 docs/cost.md）：
//   $env:PRICE_IN="0.18"; $env:PRICE_OUT="0.73"; k6 run perf/tokens.js
// 指標不在 BASE_URL 上（例如 actuator 改到另一個埠）時，覆蓋 METRICS_URL。

import http from 'k6/http';
import { BASE_URL, MESSAGES, say, signIn, useSession } from './lib/assistant.js';

const ROUNDS = Number(__ENV.ROUNDS || 1);
const METRICS_URL = __ENV.METRICS_URL || `${BASE_URL}/actuator/prometheus`;
const PRICE_IN = Number(__ENV.PRICE_IN || 0.18);
const PRICE_OUT = Number(__ENV.PRICE_OUT || 0.73);

export const options = {
  vus: 1,
  iterations: MESSAGES.length * ROUNDS,
  thresholds: {
    // 有一句失敗，平均就少算了那一句，數字不能用
    checks: ['rate==1.0'],
  },
};

/**
 * 把 Prometheus 文字格式裡的 token counter 和「叫了幾次模型」加總起來。
 * 依 token 種類（input / output）和 model_backend 分開；其他標籤（模型名稱等）加在一起。
 */
function snapshot() {
  const res = http.get(METRICS_URL);
  if (res.status !== 200) {
    throw new Error(`讀不到指標 ${METRICS_URL}：${res.status}`);
  }
  const totals = {};
  for (const line of res.body.split('\n')) {
    let m = line.match(/^gen_ai_client_token_usage_total\{(.*)\}\s+(\S+)/);
    if (m) {
      const type = (m[1].match(/gen_ai_token_type="([^"]*)"/) || [])[1];
      const backend = (m[1].match(/model_backend="([^"]*)"/) || [])[1] || '(沒有標籤)';
      const key = `${backend} ${type}`;
      totals[key] = (totals[key] || 0) + Number(m[2]);
      continue;
    }
    m = line.match(/^gen_ai_client_operation_seconds_count\{(.*)\}\s+(\S+)/);
    if (m) {
      totals['model calls'] = (totals['model calls'] || 0) + Number(m[2]);
    }
  }
  return totals;
}

export function setup() {
  return { session: signIn(), before: snapshot() };
}

export default function (data) {
  useSession(data.session);
  say(MESSAGES[__ITER % MESSAGES.length]);
}

export function teardown(data) {
  const after = snapshot();
  const delta = (key) => (after[key] || 0) - (data.before[key] || 0);
  const requests = MESSAGES.length * ROUNDS;
  const backends = [...new Set(Object.keys(after).filter((k) => k !== 'model calls').map((k) => k.split(' ')[0]))];

  console.log(`\n請求 ${requests} 個，叫模型 ${delta('model calls')} 次（平均每個請求 ${(delta('model calls') / requests).toFixed(2)} 次）`);
  for (const backend of backends) {
    const input = delta(`${backend} input`);
    const output = delta(`${backend} output`);
    if (input === 0 && output === 0) continue;
    const perReqIn = input / requests;
    const perReqOut = output / requests;
    const usd = (perReqIn * PRICE_IN + perReqOut * PRICE_OUT) / 1e6;
    console.log(`[${backend}] 每個請求平均 input ${perReqIn.toFixed(0)}、output ${perReqOut.toFixed(0)} token；`
      + `照 ${PRICE_IN}/${PRICE_OUT} USD 每 1M token 算，每個請求 ${usd.toFixed(6)} USD`);
  }
}
