// smoke —— 最小規模的「路走得通嗎」測試。
//
// 不是壓力測試：1 個虛擬使用者、跑幾次就好。目的是在花力氣做真正的負載測試之前，
// 先單獨證明一件事 —— k6 打得到 /api/chat，而且兩側契約都對得上。
// 跟 ChatController 那句「先把第二個入口能通這件事單獨做完、單獨驗證」是同一個心態。
//
// 怎麼跑（app 要先在 8090 上跑起來）：
//   k6 run perf/smoke.js
// 指到別台機器（例如容器裡的 app）就覆蓋 BASE_URL，不必改這支腳本：
//   PowerShell:  $env:BASE_URL="http://192.168.0.10:8090"; k6 run perf/smoke.js
//   bash:        BASE_URL=http://192.168.0.10:8090 k6 run perf/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CHAT_URL, QUESTIONS, ask, jsonParams } from './lib/chat.js';

export const options = {
  // 最小規模：一個人、問幾輪。smoke 要的是「快、確定」，不是「多」。
  vus: 1,
  iterations: 3,

  // k6 判定成敗的門檻。沒過的話 k6 會以非 0 結束碼收工，CI 才擋得住。
  thresholds: {
    // 契約要 100% 正確：狀態碼、格式錯了就是壞，沒有「99% 就好」的空間 ——
    // smoke 總共才問幾次，錯一次就是真的錯，不是抖動。
    checks: ['rate==1.0'],

    // 為什麼延遲門檻放這麼寬？因為每個請求後面是一顆真的 LLM 在生字，
    // Qwen3 的 thinking 會先燒掉幾百個看不見的 token，好幾秒才回來是正常的。
    // 拿一般 web API 的 p95<500ms 來套只會全紅、量錯東西。
    // 這裡的 60 秒不是「效能目標」，是「離譜線」：超過它八成是卡住而不是慢。
    http_req_duration: ['p(95)<60000'],
  },
};

export default function () {
  contractRejectsBlankQuestion();
  // 契約的另一半：正常問題該回 200、reply 要有東西（檢查寫在 lib/chat.js 的 ask）。
  // 照順序輪流問池子裡的前幾題 —— smoke 要可重現，不要隨機。
  ask(QUESTIONS[__ITER % QUESTIONS.length]);

  // 每輪之間喘一下，別把單機的 llama.cpp 逼到滿載 —— smoke 只是要證明能通，不是要壓垮它。
  sleep(1);
}

// 契約的一半：空白問題該被擋成 400。
// 這條由 Conversation 用 IllegalArgumentException 擋、ChatController 翻成 400。
// 它完全不碰模型，所以又快又穩 —— 就算 llama.cpp 沒開，這個 check 也該過。
// 模型掛掉時，ask 那條會紅、這裡會綠，一眼看出是「模型層 down」而不是「整條路壞了」。
function contractRejectsBlankQuestion() {
  // 告訴 k6「這個請求回 400 才是對的」。不標的話 k6 把所有非 2xx 都算進 http_req_failed，
  // 報表上會出現一個嚇人的 50% 失敗率 —— 其實全是這些故意送錯的請求。
  const params = jsonParams({ responseCallback: http.expectedStatuses(400) });
  const res = http.post(CHAT_URL, JSON.stringify({ question: '   ' }), params);
  check(res, {
    '空白問題回 400': (r) => r.status === 400,
  });
}
