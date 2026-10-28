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
// 指標在 app 的 management 埠（8091），不在 API 的 8090 上：預設把 BASE_URL 結尾的 :8090 換成 :8091。
// docker 那台 app 的 8091 故意不發佈到主機（deploy/compose.yaml），所以這支要打本機 bootRun；別的位置就覆蓋 METRICS_URL。

import http from 'k6/http';
import { BASE_URL, MESSAGES, say, signIn, useSession } from './lib/assistant.js';

const ROUNDS = Number(__ENV.ROUNDS || 1);
const METRICS_URL = __ENV.METRICS_URL || `${BASE_URL.replace(/:8090$/, ':8091')}/actuator/prometheus`;
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
 * 把 Prometheus 文字格式裡的 token counter、「叫了幾次模型」和「助理請求數」加總起來。
 * token 依種類（input / output）和 model_backend 分開；其他標籤（模型名稱等）加在一起。
 * 請求數跟成本 dashboard 的分母同一個條件（POST /api/calendar/assistant，不含 4xx）：
 * 用 app 實際處理完的數量，不用 k6 預定的 iterations —— k6 跑到 maxDuration（預設 10 分鐘）會停，
 * 沒跑完的那幾輪不算 check 失敗，拿預定數當分母平均就少算了。
 */
function snapshot() {
  const res = http.get(METRICS_URL);
  if (res.status !== 200) {
    // status 0 = 連不上：多半是打了 docker 的 app（management 埠沒發佈）或 bootRun 沒起來
    throw new Error(`讀不到指標 ${METRICS_URL}：${res.status || res.error}。指標在 management 埠，見這支開頭的說明`);
  }
  const totals = { calls: 0, requests: 0, tokens: {} };
  for (const line of res.body.split('\n')) {
    const m = line.match(/^(\w+)\{(.*)\}\s+(\S+)/);
    if (!m) continue;
    const [, name, labels, value] = m;
    if (name === 'gen_ai_client_token_usage_total') {
      const type = (labels.match(/gen_ai_token_type="([^"]*)"/) || [])[1];
      const backend = (labels.match(/model_backend="([^"]*)"/) || [])[1] || '(沒有標籤)';
      const byType = (totals.tokens[backend] ||= {});
      byType[type] = (byType[type] || 0) + Number(value);
    } else if (name === 'gen_ai_client_operation_seconds_count') {
      totals.calls += Number(value);
    } else if (name === 'http_server_requests_seconds_count'
        && labels.includes('uri="/api/calendar/assistant"')
        && labels.includes('method="POST"')
        && !/status="4/.test(labels)) {
      totals.requests += Number(value);
    }
  }
  return totals;
}

export function setup() {
  // 先讀指標再登入：指標讀不到時馬上停，不必先註冊、登入一輪。登入不會叫模型，不影響相減的結果
  const before = snapshot();
  return { session: signIn(), before };
}

export default function (data) {
  useSession(data.session);
  say(MESSAGES[__ITER % MESSAGES.length]);
}

export function teardown(data) {
  const after = snapshot();
  const tokens = (snap, backend, type) => snap.tokens[backend]?.[type] || 0;
  const delta = (backend, type) => tokens(after, backend, type) - tokens(data.before, backend, type);
  const calls = after.calls - data.before.calls;
  const requests = after.requests - data.before.requests;
  const backends = Object.keys(after.tokens);
  // counter 只會往上加；變小就是 app 中途重啟、歸零了，相減出來的數字沒有意義
  if (calls < 0 || requests < 0 || backends.some((b) => delta(b, 'input') < 0 || delta(b, 'output') < 0)) {
    throw new Error('指標比開始時還小：app 跑到一半重啟過，counter 歸零了。重跑一次');
  }
  if (requests === 0) {
    throw new Error('app 沒有記到任何助理請求，沒辦法算平均');
  }

  console.log(`\n請求 ${requests} 個（預定 ${MESSAGES.length * ROUNDS} 個），叫模型 ${calls} 次（平均每個請求 ${(calls / requests).toFixed(2)} 次）`);
  for (const backend of backends) {
    const input = delta(backend, 'input');
    const output = delta(backend, 'output');
    if (input === 0 && output === 0) continue;
    const perReqIn = input / requests;
    const perReqOut = output / requests;
    const usd = (perReqIn * PRICE_IN + perReqOut * PRICE_OUT) / 1e6;
    console.log(`[${backend}] 每個請求平均 input ${perReqIn.toFixed(0)}、output ${perReqOut.toFixed(0)} token；`
      + `照 ${PRICE_IN}/${PRICE_OUT} USD 每 1M token 算，每個請求 ${usd.toFixed(6)} USD`);
  }
}
