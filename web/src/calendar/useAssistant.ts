// AI 助理：跟後端的 agent（POST /api/calendar/assistant）對話。
//
// 助理自己決定要做什麼：時間不確定就反問、查行程、找空檔，或提議行程。
// 提議的行程顯示成卡片 —— 還是「先預覽再確認」：按了「加入行事曆」才送 /events 存起來。
// 一句話可以提議好幾筆，每筆一張卡片；可以先「修改」（帶到新增表單）或「移除」某一筆，再一次全部加入。
//
// 對話的記憶在後端，這裡只記 conversationId（存在 localStorage）和畫面上的訊息。
// 重新整理之後，用 conversationId 向後端讀回那段對話的文字接著聊；按「清空」只是丟掉這個 id，下一句由後端發新的 id、從頭開始。
// 後端那段不刪：清空是「這台瀏覽器不再接那段」，不是銷毀紀錄；沒人接的舊對話由後端每天的清理排程處理（30 天）。
// 讀回來的舊訊息不帶卡片：卡片當時是加入還是取消，後端沒有記，顯示可能已過時的卡片不如不顯示。

import { inject, ref, type InjectionKey } from 'vue'
import { assistantHistory, talkToAssistant, type EventFields } from '../calendar'
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

// 三種能做的事各舉一個例子：新增、查詢、找空檔
export const SUGGESTIONS = ['明天下午3點和 Amy 開會', '明天有什麼行程？', '這週哪天下午有空？']

const GREETING = '嗨！我是你的行事曆助理。可以幫你新增行程、查某天的安排，或找出空檔。要新增的話，我會整理好讓你確認後再加入。'

// conversationId 存在這台瀏覽器：跟主題一樣是個人的東西，不必存到後端。
// 私密視窗、被封鎖的網站資料都可能讓 localStorage 丟例外，一律吞掉 —— 最壞就是重新整理後開新對話
const STORAGE_KEY = 'talkcal.calendar.assistant.conversation'

function loadConversationId(): string | undefined {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? undefined
  } catch {
    return undefined
  }
}

function saveConversationId(id: string | undefined) {
  try {
    if (id) localStorage.setItem(STORAGE_KEY, id)
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 存不了就算了，這次的對話在記憶體裡照樣接得上
  }
}

export function useAssistant(calendar: CalendarState) {
  let nextId = 1
  const say = (role: ChatMessage['role'], text: string, proposals: Proposal[] = []): ChatMessage =>
    ({ id: nextId++, role, text, proposals, error: null })

  const messages = ref<ChatMessage[]>([say('ai', GREETING)])
  const input = ref('')
  const typing = ref(false)
  const saving = ref(false)
  // 後端那段對話的身分：第一句之後才有，之後每句都帶著；重新整理後從 localStorage 拿回來
  let conversationId = loadConversationId()

  // 重新整理之後，把上次那段對話讀回來。讀的時候 typing 亮著，避免使用者在舊訊息回來前就開始打字、順序亂掉
  async function restore() {
    if (!conversationId) return
    typing.value = true
    try {
      const history = await assistantHistory(conversationId)
      if (history.messages.length === 0) {
        // 後端已經沒有這段了（被清空、資料庫重建）：當成新對話
        conversationId = undefined
        saveConversationId(undefined)
        return
      }
      messages.value = [say('ai', GREETING), ...history.messages.map((m) => say(m.role === 'user' ? 'me' : 'ai', m.text))]
    } catch (e) {
      messages.value.push(say('ai', `讀不回上次的對話：${message(e)}。可以直接開始新的對話。`))
    } finally {
      typing.value = false
    }
  }
  restore()

  /** 清空：丟掉 conversationId，下一句不帶 id，後端就開一段新的。不用等後端，也就不會清空失敗。 */
  function clear() {
    if (typing.value) return
    conversationId = undefined
    saveConversationId(undefined)
    messages.value = [say('ai', GREETING)]
    input.value = ''
  }

  async function send(text = input.value) {
    const said = text.trim()
    if (!said || typing.value) return
    messages.value.push(say('me', said))
    input.value = ''
    typing.value = true
    try {
      const answer = await talkToAssistant(said, conversationId)
      conversationId = answer.conversationId
      saveConversationId(conversationId)
      messages.value.push(say('ai', answer.reply, answer.proposals.map((draft) => ({ draft, status: 'pending' }))))
    } catch (e) {
      messages.value.push(say('ai', `助理暫時沒辦法回應：${message(e)}。可以稍後再試一次。`))
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

  return { messages, input, typing, saving, send, confirm, cancel, drop, edit, clear }
}

export type AssistantState = ReturnType<typeof useAssistant>

export const ASSISTANT: InjectionKey<AssistantState> = Symbol('assistant')

export function useAssistantState(): AssistantState {
  const state = inject(ASSISTANT)
  if (!state) throw new Error('AI 助理元件必須放在 CalendarView 底下')
  return state
}
