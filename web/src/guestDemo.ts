// 沒登入的訪客看助理示範回答的 API（POST /api/demo/assistant，後端是 GuestDemoController）。
// 只收 SUGGESTIONS 那幾句，回答是後端預先寫好的，不呼叫模型。每個 IP 每天 3 次，第 4 次回 429。

import type { EventFields } from './calendar'
import { send } from './http'

export interface GuestDemoReply {
  reply: string
  // 只給畫面看，訪客按確認時帶去登入；沒有 proposalId
  proposals: EventFields[]
  // 今天還能再看幾次
  remaining: number
}

/** 看滿了丟 ApiError（detail.status 是 429）。沒登入是正常狀態，401 不通知 App 換頁 */
export async function askGuestDemo(message: string): Promise<GuestDemoReply> {
  const res = await send('/api/demo/assistant', { method: 'POST', body: JSON.stringify({ message }) }, { notifyUnauthorized: false })
  return (await res.json()) as GuestDemoReply
}
