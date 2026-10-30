import { send } from './http'

// 404 的錯誤型別搬到 http.ts（每個 API 共用），這裡轉出去，useCalendar 照舊從這裡 import
export { NotFoundError } from './http'

// 跟後端 /api/calendar/* 的契約。形狀照抄 CalendarController 的巢狀 record，
// 後端那邊改了欄位，這裡要跟著改，而且同一個 commit 一起改：兩邊沒有共用的型別，不一起改就會悄悄對不上。
//
// 時間都是字串，格式是不帶時區的 ISO 牆上時間（例如 "2026-10-06T15:00:00"），刻意不轉成 Date：
// Date 一定帶著時區，瀏覽器會用使用者電腦的時區解讀它；而後端存的是「台北的牆上三點」，
// 字串原樣傳來傳去，三點就永遠是三點。

/** 分類在線路上的寫法，跟 CalendarController 的 toCategory／fromCategory 一致。 */
export type Category = 'WORK' | 'PERSONAL' | 'HEALTH' | 'SOCIAL'

export const CATEGORIES: readonly Category[] = ['WORK', 'PERSONAL', 'HEALTH', 'SOCIAL']

/** 一筆行程的欄位。助理的提議、確認的請求共用這個形狀：提議的卡片改完原樣送回 /events。 */
export interface EventFields {
  title: string
  start: string
  end: string
  category: Category
  // 沒有就是 null（後端把空白一律當成沒有）
  location: string | null
  note: string | null
  // 從哪張助理提議卡片來的：助理回覆的每張卡片都有，確認時原樣帶回去才算採納（#223）。手動填的沒有這個欄位
  proposalId?: string
}

/** 已經存起來的行程，多了 id。 */
export interface SavedEvent extends EventFields {
  id: string
}

/** AI 助理一輪的回覆：助理說的話，以及這一輪提議的行程（確認後送 addEvents 才存）。 */
export interface AssistantReply {
  conversationId: string
  reply: string
  proposals: EventFields[]
}

/**
 * 跟 AI 助理說一句話。助理可能反問、查行程、找空檔，或提議行程。
 * conversationId 不帶就是新對話；之後每句帶著回應裡的那一個，助理才記得前面說過什麼。
 */
export async function talkToAssistant(message: string, conversationId?: string): Promise<AssistantReply> {
  const res = await send('/api/calendar/assistant', { method: 'POST', body: JSON.stringify({ message, conversationId }) })
  const body = (await res.json()) as AssistantReply
  return { ...body, proposals: body.proposals.map(normalize) }
}

/** 一段對話給人看的部分（工具呼叫、提議過的卡片不在裡面）。 */
export interface AssistantHistory {
  conversationId: string
  messages: { role: 'user' | 'assistant'; text: string }[]
}

/** 讀回一段對話。沒有這段（從來沒有、或已經清空）就是空陣列。 */
export async function assistantHistory(conversationId: string): Promise<AssistantHistory> {
  const res = await send(`/api/calendar/assistant/${encodeURIComponent(conversationId)}`, { method: 'GET' })
  return (await res.json()) as AssistantHistory
}

/**
 * 把確認過的草稿（或手動表單）存起來。全部存或全部不存；有一筆不合規就整批 400。
 * 從助理卡片來的那幾筆帶著 proposalId，後端核對過才算採納（只用在產品指標，不影響存檔）。
 *
 * idempotencyKey（#51）：這一次新增的名字（submitOnce.ts 的 retryKey）。失敗後再送同樣的內容帶同一個，
 * 上次其實存好了的話後端只回原本那幾筆、不會多存一份。同一個鍵帶了不同內容後端回 422。
 */
export async function addEvents(events: EventFields[], idempotencyKey?: string): Promise<SavedEvent[]> {
  const headers = idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  const res = await send('/api/calendar/events', { method: 'POST', body: JSON.stringify({ events }), headers })
  return ((await res.json()) as { events: SavedEvent[] }).events.map(normalize)
}

/**
 * 告訴後端使用者按掉了哪幾張提議卡片。「取消」只改畫面、不經過後端，不回報的話後端算不出採納率。
 * 只是指標：失敗了也不該打擾使用者，呼叫端自己決定要不要理會錯誤。
 */
export async function dismissProposals(proposalIds: string[]): Promise<void> {
  await send('/api/calendar/assistant/dismissals', { method: 'POST', body: JSON.stringify({ proposalIds }) })
}

/** 期間裡的行程。from 含、to 不含，格式 yyyy-MM-dd；跨夜跨月的行程前後兩頁都會出現。 */
export async function listEvents(from: string, to: string): Promise<SavedEvent[]> {
  const query = new URLSearchParams({ from, to })
  const res = await send(`/api/calendar/events?${query}`, { method: 'GET' })
  return ((await res.json()) as { events: SavedEvent[] }).events.map(normalize)
}

/** 拿掉一個行程。已經不在了（例如另一個分頁先刪了）會丟 NotFoundError，畫面該重新整理。 */
export async function removeEvent(id: string): Promise<void> {
  await send(`/api/calendar/events/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/**
 * 收到的行程把缺的欄位補齊：分類不在就算 PERSONAL（跟後端的 Category.DEFAULT 一致），地點、備註不在就是 null。
 *
 * 新版後端一定會帶這幾個欄位，這是給「前端先更新、後端還是舊版」的時候用的：
 * 前後端不一定同時部署，舊版後端回的行程沒有 category，畫面不該因此整個壞掉（實際發生過：分類圓點拿不到顏色就丟例外）。
 */
function normalize<T extends EventFields>(event: T): T {
  return { ...event, category: event.category ?? 'PERSONAL', location: event.location ?? null, note: event.note ?? null }
}
