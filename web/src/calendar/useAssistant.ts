// AI 助理：一句話 → 後端 /parse 解析成行程草稿 → 卡片給使用者確認 → /events 存起來。
//
// 這就是「先預覽再確認」流程換上對話的外觀：解析不存檔，按了「加入行事曆」才存。
// 一句話可以解析出好幾筆，每筆一張卡片；可以先「修改」（帶到新增表單）或「移除」某一筆，再一次全部加入。
//
// 對話紀錄只放在記憶體：重新整理就清空。它只是操作的過程，真正的結果（行程）已經存在資料庫裡了。

import { inject, ref, type InjectionKey } from 'vue'
import { parseEvents, type EventFields } from '../calendar'
import { message, type CalendarState } from './useCalendar'

export type ProposalStatus = 'pending' | 'added' | 'removed'

export interface Proposal {
  draft: EventFields
  status: ProposalStatus
}

export interface ChatMessage {
  id: number
  role: 'me' | 'ai'
  text: string
  proposals: Proposal[]
  // 加入行事曆失敗時的原因，顯示在卡片下面；草稿留著讓使用者再按一次
  error: string | null
}

// 建議的說法只放「新增」：查詢行程、找空檔這一輪沒做，放了只會讓人按了失望
export const SUGGESTIONS = ['明天下午3點和 Amy 開會', '週五晚上7點在拉麵店聚餐', '下週三早上9點到10點半看牙醫']

export function useAssistant(calendar: CalendarState) {
  let nextId = 1
  const say = (role: ChatMessage['role'], text: string, proposals: Proposal[] = []): ChatMessage =>
    ({ id: nextId++, role, text, proposals, error: null })

  const messages = ref<ChatMessage[]>([
    say('ai', '嗨！我是你的行事曆助理。用一句話告訴我要排什麼，例如「明天下午3點和 Amy 開會」，我整理好讓你確認後再加入。'),
  ])
  const input = ref('')
  const typing = ref(false)
  const saving = ref(false)

  async function send(text = input.value) {
    const said = text.trim()
    if (!said || typing.value) return
    messages.value.push(say('me', said))
    input.value = ''
    typing.value = true
    try {
      const drafts = await parseEvents(said)
      if (drafts.length === 0) {
        messages.value.push(say('ai', '這句話裡我沒看出行程。可以說得更具體一點，例如「週五晚上7點聚餐」「下週二早上10點面試」。'))
      } else {
        const lead = drafts.length === 1 ? '幫你整理好了，確認後就會加入行事曆：' : `我看出 ${drafts.length} 個行程，確認後一起加入行事曆：`
        messages.value.push(say('ai', lead, drafts.map((draft) => ({ draft, status: 'pending' }))))
      }
    } catch (e) {
      messages.value.push(say('ai', `解析失敗：${message(e)}。可以稍後再試一次。`))
    } finally {
      typing.value = false
    }
  }

  /** 把這則訊息裡還沒處理的草稿一次存起來（後端是全部存或全部不存）。 */
  async function confirm(msg: ChatMessage) {
    const pending = msg.proposals.filter((p) => p.status === 'pending')
    if (pending.length === 0 || saving.value) return
    saving.value = true
    msg.error = null
    try {
      await calendar.add(pending.map((p) => p.draft))
      for (const p of pending) p.status = 'added'
    } catch (e) {
      msg.error = message(e)
    } finally {
      saving.value = false
    }
  }

  function cancel(msg: ChatMessage) {
    for (const p of msg.proposals) if (p.status === 'pending') p.status = 'removed'
  }

  function drop(proposal: Proposal) {
    proposal.status = 'removed'
  }

  /** 帶著這筆草稿打開新增表單；在表單裡存好了，這張卡片就算「已加入」。 */
  function edit(proposal: Proposal) {
    calendar.openForm(proposal.draft, () => {
      proposal.status = 'added'
    })
  }

  return { messages, input, typing, saving, send, confirm, cancel, drop, edit }
}

export type AssistantState = ReturnType<typeof useAssistant>

export const ASSISTANT: InjectionKey<AssistantState> = Symbol('assistant')

export function useAssistantState(): AssistantState {
  const state = inject(ASSISTANT)
  if (!state) throw new Error('AI 助理元件必須放在 CalendarView 底下')
  return state
}
