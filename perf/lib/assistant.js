// 各支壓測腳本共用的請求：位址、登入、跟 AI 助理（POST /api/calendar/assistant）說話和檢查回覆。
// 送多少、送多久、門檻多少由各腳本自己定。
//
// 助理的一個請求是一段 agent loop：可能呼叫模型好幾次（選工具、生成回覆，提議行程時工具還會再叫一次模型抽行程），
// 所以延遲看句子差很多。助理也會讀寫 PostgreSQL。
// 每個請求都不帶 conversationId，等於開一段新對話：壓一次就在 calendar_assistant_message 留下一堆對話，
// 不想留就對著用完即丟的資料庫壓。助理只提議行程，不會新增。

import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
export const ASSISTANT_URL = `${BASE_URL}/api/calendar/assistant`;

// 句子短，生成長度才有上界。句子要多樣：併發時大家說同一句，會命中 llama.cpp 的 prompt 快取，量出來的延遲比實際短。
// 查詢（list_events、find_free_slots）和提議（propose_events）都要有
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

export function jsonParams(extra) {
  const headers = { 'Content-Type': 'application/json' };
  if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
  return Object.assign(
    {
      headers,
      // app 叫一次模型最多等 2 分鐘。k6 預設 60 秒就斷線，會把模型還在生成誤判成 app 停止回應；
      // 剛好 120 秒的話兩邊同時到點，k6 常在 app 回 502 之前先斷線，演練記成沒拿到回應。
      // 一輪可能叫好幾次模型，這個上限只擋卡死
      timeout: '150s',
    },
    extra,
  );
}

export function say(message) {
  const res = http.post(ASSISTANT_URL, JSON.stringify({ message }), jsonParams());
  check(res, {
    '正常訊息回 200': (r) => r.status === 200,
    'reply 非空白': (r) => field(r, 'reply').trim().length > 0,
    '拿到 conversationId': (r) => field(r, 'conversationId').length > 0,
  });
  return res;
}

// body 不是預期的 JSON（例如 502 的錯誤物件）時 res.json() 會丟例外，回空字串讓 check 判成沒拿到
export function field(res, name) {
  try {
    return res.json(name) || '';
  } catch (e) {
    return '';
  }
}

// 只碰資料庫、不呼叫模型的請求。各腳本都讀同一個月，結果才比得起來
export function listMonth() {
  return http.get(`${BASE_URL}/api/calendar/events?from=2026-10-01&to=2026-11-01`);
}

// ── 登入 ──────────────────────────────────────────────────────────────
// 在 setup() 登入一次，所有 VU 共用同一個 session：nginx 對登入限流（每個 IP 每分鐘 5 次），
// 上千個 VU 各自登入會被 429 擋下。帳號沒有就自動註冊
const PERF_USER = __ENV.PERF_USER || 'perf-user';
const PERF_PASSWORD = __ENV.PERF_PASSWORD || 'perf-password-1';

// k6 的模組變數每個 VU 各一份
let csrfToken = null;

function cookie(name) {
  const values = http.cookieJar().cookiesForURL(BASE_URL)[name];
  return values && values.length ? values[0] : null;
}

/** 在 setup() 裡呼叫，回傳要分給每個 VU 的 { session, csrf }。 */
export function signIn() {
  // 先拿 XSRF-TOKEN cookie，還沒登入所以 401 是正常的
  http.get(`${BASE_URL}/api/auth/me`, { responseCallback: http.expectedStatuses(200, 401) });
  http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({ username: PERF_USER, password: PERF_PASSWORD }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': cookie('XSRF-TOKEN') },
    responseCallback: http.expectedStatuses(201, 409),
  });
  // 登入是表單格式（Spring Security 的 formLogin），k6 的 body 給物件就送成表單
  const res = http.post(`${BASE_URL}/api/auth/login`, { username: PERF_USER, password: PERF_PASSWORD }, {
    headers: { 'X-XSRF-TOKEN': cookie('XSRF-TOKEN') },
  });
  if (!check(res, { '登入成功（204）': (r) => r.status === 204 })) {
    throw new Error(`登入失敗：${res.status} ${res.body}`);
  }
  // 登入後 CSRF token 會換新
  http.get(`${BASE_URL}/api/auth/me`);
  return { session: cookie('JSESSIONID'), csrf: cookie('XSRF-TOKEN') };
}

/**
 * 每一次迭代開頭都要呼叫：k6 預設每次迭代開始時清空 cookie jar，只放一次的話第二次迭代起都是 401。
 */
export function useSession(session) {
  http.cookieJar().set(BASE_URL, 'JSESSIONID', session.session);
  http.cookieJar().set(BASE_URL, 'XSRF-TOKEN', session.csrf);
  csrfToken = session.csrf;
}
