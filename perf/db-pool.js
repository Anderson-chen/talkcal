// db-pool —— 把 app 的資料庫連線池（HikariCP，預設 10 條）壓到不夠用，看 dashboard 上 pending 從 0 爬起來（#32）。
//
// 其他腳本都壓 AI 助理，瓶頸在模型（一輪好幾秒），資料庫連線只借幾毫秒，池子永遠用不滿。
// 這支改壓只碰資料庫的 GET /api/calendar/events（月曆一頁）：不叫模型，一個請求就是「借連線 → 查 → 還」。
// 同時在跑的請求一多過池子的 10 條，多出來的就得排隊等連線 —— 就是要讓這件事在圖上看得到。
//
// 看哪裡：Grafana 的 talkcal — Spring app（http://localhost:3000/d/talkcal-app）的「資料庫連線池」那一排：
//   - 連線 active 貼上 max 的虛線（10）
//   - pending 離開 0
//   - acquire（等連線）從微秒跳到毫秒
// 指標每 15 秒抓一次，每一階要撐得比 15 秒久，圖上才抓得到。
//
// 預設打 docker 那台 app（18090，Alloy 只抓它的指標），全程約 2 分半：
//   perf/run-to-grafana.sh perf/db-pool.js
// 自訂每一階的 VU 數、每階多久：
//   STAGES=20,100,300 HOLD_S=30 perf/run-to-grafana.sh perf/db-pool.js
//
// 為什麼最多到 300：Tomcat 預設最多 200 條執行緒同時處理請求，超過的在 Tomcat 的佇列裡等、碰不到連線池。
// 所以 pending 最多大約 200 − 10；再加 VU 只會讓 Tomcat 那層排隊，連線池的圖不會再變。

import './lib/docker-target.js';
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, jsonParams, signIn, useSession } from './lib/assistant.js';

const STAGES = (__ENV.STAGES || '5,50,150,300').split(',').map(Number);
const HOLD_S = Number(__ENV.HOLD_S || 30);

// 用一段很遠的期間（2030 年），種的假行程不會出現在 perf-user 平常會看的月份
const FROM = '2030-01-01';
const TO = '2030-04-01';            // 90 天，在 API 的上限（100 天）內
const SEED_EVENTS = 200;            // 每次查詢回幾筆：讓查詢真的有事做，連線被占用的時間不是 0

export const options = {
  // ramping-vus：一階一階往上加，每一階停 HOLD_S 秒（爬坡 5 秒）。
  // 不 sleep：VU 數 = 持續壓在 app 上的請求數
  scenarios: {
    pool: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: STAGES.flatMap((vus) => [
        { duration: '5s', target: vus },
        { duration: `${HOLD_S}s`, target: vus },
      ]),
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // 池子不夠用時請求只是變慢（排隊），不該失敗；失敗代表等連線超過 connectionTimeout（30 秒）了
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // 登入一次，所有 VU 共用（理由見 lib/assistant.js 的 signIn）
  const session = signIn();
  useSession(session);
  // 已經種過就不再種：重跑這支不會讓那段期間的行程一直變多、查詢一次比一次重
  const existing = http.get(eventsUrl()).json('events') || [];
  if (existing.length < SEED_EVENTS) {
    const events = [];
    for (let i = existing.length; i < SEED_EVENTS; i++) {
      const day = String(1 + (i % 28)).padStart(2, '0');
      const month = String(1 + Math.floor(i / 28) % 3).padStart(2, '0');
      const hour = String(8 + (i % 10)).padStart(2, '0');
      events.push({
        title: `壓測用行程 ${i}`,
        start: `2030-${month}-${day}T${hour}:00:00`,
        end: `2030-${month}-${day}T${hour}:30:00`,
        category: 'WORK',
      });
    }
    const res = http.post(`${BASE_URL}/api/calendar/events`, JSON.stringify({ events }), jsonParams());
    check(res, { '種行程（201）': (r) => r.status === 201 });
  }
  return session;
}

export default function (session) {
  useSession(session);
  const res = http.get(eventsUrl());
  check(res, { '讀月曆回 200': (r) => r.status === 200 });
}

function eventsUrl() {
  return `${BASE_URL}/api/calendar/events?from=${FROM}&to=${TO}`;
}
