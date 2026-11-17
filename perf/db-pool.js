// db-pool：把 app 的 HikariCP 連線池（預設 10 條）壓到不夠用，看 pending 從 0 往上升。
//
// 壓助理的話瓶頸在模型，連線只借幾毫秒，池子用不滿。所以這支腳本只壓碰資料庫、不叫模型的 GET /api/calendar/events。
//
// 看 Grafana 的 talkcal-app dashboard（http://localhost:3000/d/talkcal-app）「資料庫連線池」那一排：
// active 貼到 max（10）、pending 離開 0、acquire 從微秒跳到毫秒。
// 指標每 15 秒抓一次，每一階要維持超過 15 秒，圖上才看得到。
//
// 預設送到 docker 那台 app（18090，Alloy 只抓這台的指標），全程約 2 分半：
//   perf/run-to-grafana.sh perf/db-pool.js
// 自訂每一階的 VU 數、每階多久：
//   STAGES=20,100,300 HOLD_S=30 perf/run-to-grafana.sh perf/db-pool.js
//
// 最多到 300：Tomcat 預設最多 200 條執行緒，多的請求在 Tomcat 的佇列裡等、碰不到連線池，
// pending 最多約 200 − 10，再加 VU 連線池的圖也不會變。

import './lib/docker-target.js';
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, jsonParams, signIn, useSession } from './lib/assistant.js';

const STAGES = (__ENV.STAGES || '5,50,150,300').split(',').map(Number);
const HOLD_S = Number(__ENV.HOLD_S || 30);

// 種的行程放在 2030 年，不會出現在 perf-user 平常會看的月份
const FROM = '2030-01-01';
const TO = '2030-04-01';            // 90 天：API 一次最多查 100 天
const SEED_EVENTS = 200;            // 查詢要真的有事做，連線才會被占住一段時間

export const options = {
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
    // 池子不夠用時請求只是排隊變慢，失敗代表等連線超過 HikariCP 的 connectionTimeout（30 秒）
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // 登入一次，所有 VU 共用（理由見 lib/assistant.js 的登入那段）
  const session = signIn();
  useSession(session);
  // 已經種過就不再種，不然每重新執行一次，查詢就重一點
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
