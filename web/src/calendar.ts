import { send } from './http'

export { NotFoundError } from './http'

// 跟後端 /api/calendar/* 的契約，形狀照 CalendarController、CalendarAssistantController、ArchiveController 的 record。兩邊沒有共用的型別，
// 後端改了欄位要在同一個 commit 改這裡，不然格式對不上，編譯也不會報錯。
//
// 時間是不帶時區的 ISO 牆上時間字串（例如 "2026-10-06T15:00:00"），不要轉成 Date：Date 一定帶時區，
// 瀏覽器會用使用者電腦的時區解讀。字串原樣傳，後端存的台北三點就一直是三點。

/** API 傳輸時的寫法，對照見 CalendarController 的 toCategory／fromCategory */
export type Category = 'WORK' | 'PERSONAL' | 'HEALTH' | 'SOCIAL'

export const CATEGORIES: readonly Category[] = ['WORK', 'PERSONAL', 'HEALTH', 'SOCIAL']

/** 助理的提議和存檔的請求共用這個形狀：卡片改完原樣送回 /events */
export interface EventFields {
  title: string
  start: string
  end: string
  category: Category
  // 沒有是 null，後端把空白也當成沒有
  location: string | null
  note: string | null
  // 從哪張助理卡片來的，確認時原樣帶回去才算採納；手動填的沒有
  proposalId?: string
}

export interface SavedEvent extends EventFields {
  id: string
}

export interface AssistantChange {
  eventId: string
  before: EventFields
  after: EventFields
}

export interface AssistantReply {
  // 沒登入的訪客沒有對話可以接續，是 null
  conversationId: string | null
  reply: string
  proposals: EventFields[]
  changes: AssistantChange[]
  removals: SavedEvent[]
  // 沒登入時才有：這個 IP 今天還能問幾次
  guestRemaining?: number
}

/** conversationId 不帶是新對話；之後每句帶著回應裡的那一個，後端才讀得到前面的對話 */
export async function talkToAssistant(message: string, conversationId?: string): Promise<AssistantReply> {
  const res = await send('/api/calendar/assistant', { method: 'POST', body: JSON.stringify({ message, conversationId }) })
  const body = (await res.json()) as AssistantReply
  return {
    ...body,
    proposals: body.proposals.map(normalize),
    // 舊版後端沒有這兩個欄位（見 normalize）
    changes: (body.changes ?? []).map((c) => ({ ...c, before: normalize(c.before), after: normalize(c.after) })),
    removals: (body.removals ?? []).map(normalize),
  }
}

/** 給人看的部分，工具呼叫、提議過的卡片不在裡面 */
export interface AssistantHistory {
  conversationId: string
  messages: { role: 'user' | 'assistant'; text: string }[]
}

/** 沒有這段（從來沒有、已經被清掉）回空陣列 */
export async function assistantHistory(conversationId: string): Promise<AssistantHistory> {
  const res = await send(`/api/calendar/assistant/${encodeURIComponent(conversationId)}`, { method: 'GET' })
  return (await res.json()) as AssistantHistory
}

/**
 * 全部存或全部不存，有一筆不合規就整批 400。proposalId 只用在採納率，不影響存檔。
 *
 * idempotencyKey：失敗後再送同樣的內容帶同一個，上次其實存好了的話後端只回原本那幾筆。
 * 同一個鍵帶了不同內容回 422。
 */
export async function addEvents(events: EventFields[], idempotencyKey?: string): Promise<SavedEvent[]> {
  const headers = idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  const res = await send('/api/calendar/events', { method: 'POST', body: JSON.stringify({ events }), headers })
  return ((await res.json()) as { events: SavedEvent[] }).events.map(normalize)
}

/** 「取消」卡片只改畫面，不回報的話後端算不出採納率 */
export async function dismissProposals(proposalIds: string[]): Promise<void> {
  await send('/api/calendar/assistant/dismissals', { method: 'POST', body: JSON.stringify({ proposalIds }) })
}

/** from 含、to 不含，格式 yyyy-MM-dd；跨月的行程前後兩頁都會出現 */
export function listEvents(from: string, to: string): Promise<SavedEvent[]> {
  return eventsBetween('/api/calendar/events', from, to)
}

/**
 * 結束超過 2 年、搬到封存的行程，listEvents 讀不到。from、to 都含（看一天就給同一天），跟範圍有重疊的都算。
 * 範圍不合規回 400，封存讀不到回 502。
 */
export function listArchivedEvents(from: string, to: string): Promise<SavedEvent[]> {
  return eventsBetween('/api/calendar/archive/events', from, to)
}

// 月曆和封存回同一個形狀（後端共用 CalendarController.SavedEvents）
async function eventsBetween(path: string, from: string, to: string): Promise<SavedEvent[]> {
  const query = new URLSearchParams({ from, to })
  const res = await send(`${path}?${query}`, { method: 'GET' })
  return ((await res.json()) as { events: SavedEvent[] }).events.map(normalize)
}

/**
 * expected 是改之前看到的樣子：那筆在這之後被改過，後端回 409（ApiError）、不改；已經不在丟 NotFoundError。
 */
export async function updateEvent(id: string, expected: EventFields, event: EventFields): Promise<SavedEvent> {
  const body = JSON.stringify({ expected: fieldsOf(expected), event: fieldsOf(event) })
  const res = await send(`/api/calendar/events/${encodeURIComponent(id)}`, { method: 'PUT', body })
  return normalize((await res.json()) as SavedEvent)
}

// 讀回來的那筆帶著 id、卡片的草稿可能帶著 proposalId，都不是行程本身的欄位，不送
function fieldsOf(event: EventFields): EventFields {
  const { title, start, end, category, location, note } = event
  return { title, start, end, category, location, note }
}

/** 已經不在（例如另一個分頁先刪了）丟 NotFoundError */
export async function removeEvent(id: string): Promise<void> {
  await send(`/api/calendar/events/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/**
 * 前後端不一定同時部署，舊版後端回的行程可能少了 category、location、note。
 * 補成後端的預設（category 是 Category.DEFAULT，也就是 PERSONAL）：少了 category，分類圓點拿不到顏色會丟例外。
 */
function normalize<T extends EventFields>(event: T): T {
  return { ...event, category: event.category ?? 'PERSONAL', location: event.location ?? null, note: event.note ?? null }
}
