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

// 位址走環境變數而不是寫死 —— 跟 Application.java 用 -Dllamacpp.baseUri 指位址是同個道理：
// 換目標不必動程式。沒給就打本機常駐的那台。
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const CHAT_URL = `${BASE_URL}/api/chat`;

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

// 幾個短問題輪流問。刻意短，讓生成時間有上界；刻意不同，避免每次都命中同一份快取、
// 測出不真實的「假快」。
const QUESTIONS = [
  '用一句話介紹你自己。',
  '2 加 2 等於多少？',
  '推薦一種台灣小吃，只要名字。',
];

export default function () {
  contractRejectsBlankQuestion();
  happyPathReturnsReply();

  // 每輪之間喘一下，別把單機的 llama.cpp 逼到滿載 —— smoke 只是要證明能通，不是要壓垮它。
  sleep(1);
}

// 契約的一半：空白問題該被擋成 400。
// 這條由 Conversation 用 IllegalArgumentException 擋、ChatController 翻成 400。
// 它完全不碰模型，所以又快又穩 —— 就算 llama.cpp 沒開，這個 check 也該過。
// 模型掛掉時，下面 happyPath 會紅、這裡會綠，一眼看出是「模型層 down」而不是「整條路壞了」。
function contractRejectsBlankQuestion() {
  const res = http.post(CHAT_URL, JSON.stringify({ question: '   ' }), jsonParams());
  check(res, {
    '空白問題回 400': (r) => r.status === 400,
  });
}

// 契約的另一半：正常問題該回 200，而且 reply 要有東西。
// 這條會真的叫模型生一次字，是這支 smoke 裡唯一「慢」的部分。
function happyPathReturnsReply() {
  const question = QUESTIONS[__ITER % QUESTIONS.length];
  const res = http.post(CHAT_URL, JSON.stringify({ question }), jsonParams());
  check(res, {
    '正常問題回 200': (r) => r.status === 200,
    'reply 非空白': (r) => replyText(r).length > 0,
  });
}

function jsonParams() {
  return {
    headers: { 'Content-Type': 'application/json' },
    // app 對上游 llama.cpp 給了 2 分鐘逾時，k6 這端也要放寬到同等級。
    // 不設的話 k6 預設 60 秒就自己斷線，你會把「模型還在想」誤判成「app 掛了」。
    timeout: '120s',
  };
}

// 把 reply 安全地挖出來：body 不是預期的 JSON（例如 502 回了錯誤物件）時不要讓腳本自己炸，
// 回空字串讓上面的 check 判它是「沒拿到回覆」就好。
function replyText(res) {
  try {
    return (res.json('reply') || '').trim();
  } catch (e) {
    return '';
  }
}
