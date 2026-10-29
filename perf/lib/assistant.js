// 跟 AI 助理（POST /api/calendar/assistant）講話的共用零件 —— 每支腳本都從這裡拿。
//
// 為什麼抽出來：smoke 之外有了第二支腳本（load.js），同樣的位址、參數、檢查寫兩份就會開始各自走樣。
//
// 這裡只放「怎麼跟這個 endpoint 講話」：位址、請求參數、正常回覆長什麼樣。
// 「打多少、打多久、門檻多少」是各腳本自己的決定，不放這裡。
//
// 跟以前壓 /api/chat（一問一答、叫一次模型）不同，助理的一個請求是一段 agent loop：
//   - 可能叫模型好幾次：先決定要用哪個工具，工具回來再生成回覆；提議行程時工具本身還會再叫一次模型抽行程。
//     所以同一個併發數下，延遲比一問一答長，而且看句子差很多（查行程 vs 提議新行程）。
//   - 會讀寫 PostgreSQL：讀行程、存這段對話的記憶。資料庫要先起來。
//   - 每個請求都不帶 conversationId，等於開一段新對話。壓一次就會在 calendar_assistant_message 留下一堆對話，
//     AssistantMemoryCleanup 會在 30 天後清掉；不想留就對著用完即丟的資料庫壓。
//   - 不會新增行程：助理只「提議」，要使用者確認、另外送 POST /api/calendar/events 才會存。

import http from 'k6/http';
import { check } from 'k6';

// 位址走環境變數而不是寫死：換目標不必動程式。沒給就打本機 bootRun 的那台。
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
export const ASSISTANT_URL = `${BASE_URL}/api/calendar/assistant`;

// 訊息池。刻意短，讓生成長度有上界；
// 刻意多樣，避免併發時大家說同一句、命中 llama.cpp 的 prompt 快取而測出不真實的「假快」。
// 查詢（list_events、find_free_slots）和提議（propose_events）都有。
export const MESSAGES = [
  '明天有什麼行程？',
  '這週末有空嗎？',
  '下週一早上有哪些空檔？',
  '後天下午三點和小明喝咖啡',
  '幫我排下週三早上九點看牙醫',
  '今天晚上還有安排嗎？',
  '星期五晚上七點和家人吃飯',
  '下週有哪天下午是空的？',
  '明天中午十二點和同事吃午餐',
  '這週還剩哪些行程？',
];

// extra 讓個別請求疊加自己的設定（例如 smoke 標記「這個請求回 400 才對」），
// 共用的部分不必每支腳本重寫。
export function jsonParams(extra) {
  const headers = { 'Content-Type': 'application/json' };
  // 登入之後（#103）：會改東西的請求要帶 CSRF token，見下面的 signIn / useSession
  if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
  return Object.assign(
    {
      headers,
      // app 叫一次模型最多等 2 分鐘，k6 這端要比它長一點：剛好 120 秒的話兩邊同時到點，
      // 模型卡住時 app 回的 502 常常被 k6 自己的斷線搶先，演練記成「沒拿到回應」（#231 修好逾時後實際碰到）。
      // 不設的話 k6 預設 60 秒就自己斷線，會把「模型還在想」誤判成「app 掛了」。
      // 助理一輪可能叫好幾次模型，這個上限擋的是「卡死」，不保證每輪都在這個時間內。
      timeout: '150s',
    },
    extra,
  );
}

// 說一句正常的話，並檢查契約「正常的那一半」：回 200、reply 要有東西、拿到這段對話的 id。
// 這會真的叫模型，是整條路上最慢的部分。extra 跟 jsonParams 一樣，例如給這個請求加 tag。
export function say(message, extra) {
  const res = http.post(ASSISTANT_URL, JSON.stringify({ message }), jsonParams(extra));
  check(res, {
    '正常訊息回 200': (r) => r.status === 200,
    'reply 非空白': (r) => field(r, 'reply').trim().length > 0,
    '拿到 conversationId': (r) => field(r, 'conversationId').length > 0,
  });
  return res;
}

// 把欄位安全地挖出來：body 不是預期的 JSON（例如 502 回了錯誤物件）時不要讓腳本自己炸，
// 回空字串讓 check 判它是「沒拿到」就好。outage.js 也用它讀錯誤回應的 code
export function field(res, name) {
  try {
    return res.json(name) || '';
  } catch (e) {
    return '';
  }
}

// 月曆一頁：前端打開月曆就是這個請求，最常見。只碰資料庫、不經過模型 ——
// slo.js 拿它當平常流量，outage.js 拿它確認「模型掛了，行程 API 照常」。兩支讀的是同一個月，結果才比得起來
export function listMonth() {
  return http.get(`${BASE_URL}/api/calendar/events?from=2026-10-01&to=2026-11-01`);
}

// ── 登入（#103）────────────────────────────────────────────────────────
// /api 要登入才用得了。在 setup() 裡只登入一次，把 session cookie 和 CSRF token 分給所有 VU 共用：
// nginx 對登入限流（每個 IP 每分鐘 5 次），上千個 VU 各自登入會被 429 擋下；
// 這裡要測的是助理，不是登入流程，大家共用一個帳號就好。
// 帳號用 PERF_USER、PERF_PASSWORD 指定；沒有就自動註冊一個（已經有了就直接登入）。
const PERF_USER = __ENV.PERF_USER || 'perf-user';
const PERF_PASSWORD = __ENV.PERF_PASSWORD || 'perf-password-1';

// 這個 VU 手上的 CSRF token（k6 每個 VU 各自一份模組變數）
let csrfToken = null;

function cookie(name) {
  const values = http.cookieJar().cookiesForURL(BASE_URL)[name];
  return values && values.length ? values[0] : null;
}

/** 在 setup() 裡呼叫：註冊（已經有就略過）、登入，回傳要分給每個 VU 的 { session, csrf }。 */
export function signIn() {
  // 先 GET 一次，讓後端發 XSRF-TOKEN cookie 過來（還沒登入，回 401 是正常的）
  http.get(`${BASE_URL}/api/auth/me`, { responseCallback: http.expectedStatuses(200, 401) });
  http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({ username: PERF_USER, password: PERF_PASSWORD }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': cookie('XSRF-TOKEN') },
    responseCallback: http.expectedStatuses(201, 409),
  });
  // 登入是表單格式（Spring Security 的 formLogin）：k6 的 body 給物件就會送成表單
  const res = http.post(`${BASE_URL}/api/auth/login`, { username: PERF_USER, password: PERF_PASSWORD }, {
    headers: { 'X-XSRF-TOKEN': cookie('XSRF-TOKEN') },
  });
  if (!check(res, { '登入成功（204）': (r) => r.status === 204 })) {
    throw new Error(`登入失敗：${res.status} ${res.body}`);
  }
  // 登入之後 CSRF token 會換新，再 GET 一次拿新的
  http.get(`${BASE_URL}/api/auth/me`);
  return { session: cookie('JSESSIONID'), csrf: cookie('XSRF-TOKEN') };
}

/**
 * 在每一次迭代的開頭呼叫：把 setup 給的登入狀態放進這個 VU 的 cookie jar。
 * 每次都要放：k6 預設每次迭代開始時會清空 cookie jar（實測只放一次的話，第二次迭代起都是 401）。
 */
export function useSession(session) {
  http.cookieJar().set(BASE_URL, 'JSESSIONID', session.session);
  http.cookieJar().set(BASE_URL, 'XSRF-TOKEN', session.csrf);
  csrfToken = session.csrf;
}
