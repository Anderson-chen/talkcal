// 跟後端 /api/calendar/* 的契約。形狀照抄 CalendarController 的巢狀 record，
// 後端那邊改了欄位，這裡要跟著改（理由同 chat.ts：同一個 commit 一起改）。
//
// 時間都是字串，格式是不帶時區的 ISO 牆上時間（例如 "2026-10-06T15:00:00"），刻意不轉成 Date：
// Date 一定帶著時區，瀏覽器會用使用者電腦的時區解讀它；而後端存的是「台北的牆上三點」，
// 字串原樣傳來傳去，三點就永遠是三點。

/** 一筆行程的欄位。預覽的回應、確認的請求共用這個形狀：/parse 拿到的東西改完原樣送回 /events。 */
export interface EventFields {
  title: string
  start: string
  end: string
}

/** 已經存起來的行程，多了 id。 */
export interface SavedEvent extends EventFields {
  id: string
}

interface Failure {
  error: string
}

/** 一句話 → 行程草稿（不存）。一筆都沒解析出來會回空陣列，不是錯誤。 */
export async function parseEvents(text: string): Promise<EventFields[]> {
  const res = await send('/api/calendar/parse', { method: 'POST', body: JSON.stringify({ text }) })
  return ((await res.json()) as { events: EventFields[] }).events
}

/** 把確認過的草稿存起來。全部存或全部不存；有一筆不合規就整批 400。 */
export async function addEvents(events: EventFields[]): Promise<SavedEvent[]> {
  const res = await send('/api/calendar/events', { method: 'POST', body: JSON.stringify({ events }) })
  return ((await res.json()) as { events: SavedEvent[] }).events
}

/** 期間裡的行程。from 含、to 不含，格式 yyyy-MM-dd；跨夜跨月的行程前後兩頁都會出現。 */
export async function listEvents(from: string, to: string): Promise<SavedEvent[]> {
  const query = new URLSearchParams({ from, to })
  const res = await send(`/api/calendar/events?${query}`, { method: 'GET' })
  return ((await res.json()) as { events: SavedEvent[] }).events
}

/**
 * 錯誤處理跟 chat.ts 的 ask 一樣：非 2xx 一律丟 Error，訊息優先用後端的 CalendarFailure.error，
 * 拿不到（proxy 連不上 app，回的不是 JSON）就退回 HTTP 狀態碼。
 * 只有兩份，先不抽成共用的 —— 跟後端的 send() 同一個判斷。
 */
async function send(url: string, init: RequestInit): Promise<Response> {
  const res = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json' } })
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as Failure | null
    throw new Error(failure?.error ?? `HTTP ${res.status}`)
  }
  return res
}
