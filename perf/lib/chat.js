// 跟 /api/chat 講話的共用零件 —— smoke.js 和 load.js 都從這裡拿。
//
// 為什麼現在才抽？只有 smoke 一支腳本用的時候，抽出來是過早抽象；
// load.js 是第二個使用者，同樣的位址、參數、檢查寫兩份就會開始各自走樣，這時抽才划算。
//
// 這裡只放「怎麼跟這個 endpoint 講話」：位址、請求參數、正常回覆長什麼樣。
// 「打多少、打多久、門檻多少」是各腳本自己的決定，不放這裡。

import http from 'k6/http';
import { check } from 'k6';

// 位址走環境變數而不是寫死 —— 跟 Application.java 用系統屬性指位址是同個道理：
// 換目標不必動程式。沒給就打本機常駐的那台。
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
export const CHAT_URL = `${BASE_URL}/api/chat`;

// 問題池。刻意短、要求簡答，讓生成長度有上界；
// 刻意多樣，避免併發時大家問同一題、命中 llama.cpp 的 prompt 快取而測出不真實的「假快」。
export const QUESTIONS = [
  '用一句話介紹你自己。',
  '2 加 2 等於多少？',
  '推薦一種台灣小吃，只要名字。',
  '用一句話解釋什麼是 HTTP。',
  '台北在台灣的哪個方位？一句話。',
  '列出三種常見的水果。',
  '用一句話說明什麼是資料庫。',
  '今天天氣很好，用一句話回應我。',
  '什麼是單元測試？一句話。',
  '給我一個學習程式的建議，一句話。',
];

// extra 讓個別請求疊加自己的設定（例如 smoke 標記「這個請求回 400 才對」），
// 共用的部分不必每支腳本重寫。
export function jsonParams(extra) {
  return Object.assign(
    {
      headers: { 'Content-Type': 'application/json' },
      // app 對上游 llama.cpp 給了 2 分鐘逾時，k6 這端也要放寬到同等級。
      // 不設的話 k6 預設 60 秒就自己斷線，會把「模型還在想」誤判成「app 掛了」。
      timeout: '120s',
    },
    extra,
  );
}

// 問一題正常問題，並檢查契約「正常的那一半」：回 200，而且 reply 要有東西。
// 這會真的叫模型生一次字，是整條路上最慢的部分。
export function ask(question, extra) {
  const res = http.post(CHAT_URL, JSON.stringify({ question }), jsonParams(extra));
  checkReply(res);
  return res;
}

// 同一個請求的 http.batch 版本 —— 要「同一刻」送出好幾題時用（見 concurrent.js）。
// 跟 ask 共用位址與參數，只是不立刻送，交給 batch 一起發。
export function askRequest(question, extra) {
  return ['POST', CHAT_URL, JSON.stringify({ question }), jsonParams(extra)];
}

export function checkReply(res) {
  return check(res, {
    '正常問題回 200': (r) => r.status === 200,
    'reply 非空白': (r) => replyText(r).length > 0,
  });
}

// 把 reply 安全地挖出來：body 不是預期的 JSON（例如 502 回了錯誤物件）時不要讓腳本自己炸，
// 回空字串讓 check 判它是「沒拿到回覆」就好。
function replyText(res) {
  try {
    return (res.json('reply') || '').trim();
  } catch (e) {
    return '';
  }
}
