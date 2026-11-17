// 錯誤格式照後端 CalendarAssistantController 的 AssistantFailure：{ error, code, retryAfterSeconds? }。
import type { Page, Route } from '@playwright/test'

export type Reply = (route: Route) => Promise<void>

export const ok: Reply = (route) => route.fulfill({ status: 200, json: { conversationId: 'c1', reply: '明天沒有行程。', proposals: [] } })
/** 一直不回：模型卡住，使用者等不及就重新整理 */
export const hang: Reply = () => new Promise<void>(() => {})
export const failure = (status: number, code: string, retryAfterSeconds?: number): Reply => (route) =>
  route.fulfill({
    status,
    json: { error: '後端的話', code, ...(retryAfterSeconds ? { retryAfterSeconds } : {}) },
    headers: retryAfterSeconds ? { 'Retry-After': String(retryAfterSeconds) } : {},
  })

/** 助理照順序回這幾種；用完了就一直回最後一種。回傳收到幾次請求。 */
export async function assistantReplies(page: Page, ...replies: Reply[]) {
  const calls = { count: 0 }
  await page.route('**/api/calendar/assistant', (route) => {
    const reply = replies[Math.min(calls.count, replies.length - 1)]
    calls.count++
    return reply(route)
  })
  return calls
}
