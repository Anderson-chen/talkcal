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
//
// 回不了的時候（#43，每一種怎麼說見 assistantFailure.ts）：
// - 使用者那句留在對話裡、標成「沒送出」，底下的說明附「再試一次」：打的字不會不見，也不必重打
// - 後端說要等幾秒（模型連不上 30 秒、忙碌 5 秒、問太快 60 秒），就倒數，倒數完之前送不出去；輸入框照樣可以先打字
// - 等模型等很久時，「思考中」會說已經等了幾秒：模型最久可能想 2 分鐘，使用者要知道它還活著
// - 還沒送出的字存在這個分頁（sessionStorage）：登入過期跳回登入頁、或重新整理，回來時還在

import { computed, inject, onScopeDispose, ref, watch, type InjectionKey } from 'vue'
import { me } from '../auth'
import { assistantHistory, dismissProposals, talkToAssistant, type EventFields } from '../calendar'
import { UnauthorizedError } from '../http'
import { describeAssistantFailure, type FailureKind } from './assistantFailure'
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
  // 使用者這句沒送成功（#43）：泡泡標成「沒送出」
  failed?: boolean
  // 助理回不了的說明（#43）：canRetry 是原封不動再送有沒有意義（400 沒有，要先改）
  failure?: { kind: FailureKind; canRetry: boolean }
}

/** 「思考中」等超過這麼多秒，就改說已經等了多久。助理平常 p95 約 3 秒，等到 10 秒已經明顯不正常 */
export const SLOW_AFTER_SECONDS = 10

// 三種能做的事各舉一個例子：新增、查詢、找空檔
export const SUGGESTIONS = ['明天下午3點和 Amy 開會', '明天有什麼行程？', '這週哪天下午有空？']

const GREETING = '嗨！我是你的行事曆助理。可以幫你新增行程、查某天的安排，或找出空檔。要新增的話，我會整理好讓你確認後再加入。'

// conversationId 存在這台瀏覽器：跟主題一樣是個人的東西，不必存到後端。
// 私密視窗、被封鎖的網站資料都可能讓 localStorage 丟例外，一律吞掉 —— 最壞就是重新整理後開新對話
// 每個人各存各的（key 帶著登入者的 id，#103）：換人登入不會接回上一個人的對話。
// 真正擋住「讀別人的對話」的是後端的授權（#104），這裡只是前端不主動去要
const storageKey = () => `talkcal.calendar.assistant.conversation.${me.value?.id ?? 'anonymous'}`
// 還沒送出的字：只存在這個分頁（sessionStorage），關掉分頁就沒了 —— 草稿不必跟著瀏覽器一輩子。
// key 在建立助理時就決定，不每次現算：登入過期時 me 會先變成 null（401 的通知比例外早到），那時才算就存到別人的 key 去了
const draftKey = (userId: string | undefined) => `talkcal.calendar.assistant.draft.${userId ?? 'anonymous'}`

function loadDraft(key: string): string {
  try {
    return sessionStorage.getItem(key) ?? ''
  } catch {
    return ''
  }
}

function saveDraft(key: string, text: string) {
  try {
    if (text) sessionStorage.setItem(key, text)
    else sessionStorage.removeItem(key)
  } catch {
    // 存不了就算了：只是登入過期、重新整理後拿不回來
  }
}

function loadConversationId(): string | undefined {
  try {
    return localStorage.getItem(storageKey()) ?? undefined
  } catch {
    return undefined
  }
}

function saveConversationId(id: string | undefined) {
  try {
    if (id) localStorage.setItem(storageKey(), id)
    else localStorage.removeItem(storageKey())
  } catch {
    // 存不了就算了，這次的對話在記憶體裡照樣接得上
  }
}

export function useAssistant(calendar: CalendarState) {
  let nextId = 1
  const say = (role: ChatMessage['role'], text: string, proposals: Proposal[] = []): ChatMessage =>
    ({ id: nextId++, role, text, proposals, error: null })

  const messages = ref<ChatMessage[]>([say('ai', GREETING)])
  const draft = draftKey(me.value?.id)
  const input = ref(loadDraft(draft))
  watch(input, (text) => saveDraft(draft, text))
  const typing = ref(false)
  const saving = ref(false)

  // ── 時間：倒數「幾秒後可以再試」、「已經等了幾秒」都靠它。只在有在倒數或等待時每秒更新，平常不觸發重畫
  const now = ref(Date.now())
  // 這個時間之前送不出去（後端說的 Retry-After）
  const retryAt = ref(0)
  let typingSince = 0
  const timer = setInterval(() => {
    if (typing.value || retryAt.value > now.value) now.value = Date.now()
  }, 1000)
  onScopeDispose(() => clearInterval(timer))

  /** 還要等幾秒才能再送；0 就是現在可以 */
  const waitSeconds = computed(() => Math.max(0, Math.ceil((retryAt.value - now.value) / 1000)))
  /** 這一句已經等了幾秒（沒在等就是 0） */
  const waitedSeconds = computed(() => (typing.value ? Math.max(0, Math.floor((now.value - typingSince) / 1000)) : 0))
  // 後端那段對話的身分：第一句之後才有，之後每句都帶著；重新整理後從 localStorage 拿回來
  let conversationId = loadConversationId()

  // 重新整理之後，把上次那段對話讀回來。讀的時候 typing 亮著，避免使用者在舊訊息回來前就開始打字、順序亂掉
  async function restore() {
    if (!conversationId) return
    typing.value = true
    typingSince = now.value = Date.now()
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
  // 不等它：讀回來之前畫面先顯示招呼語。錯誤在 restore() 裡自己接住了，不會變成沒人處理的 rejection
  void restore()

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
    if (!said || typing.value || waitSeconds.value > 0) return
    // 從陣列拿回來的才是 reactive 的那一個：之後標「沒送出」要改它，畫面才會跟著變
    const mine = messages.value[messages.value.push(say('me', said)) - 1]
    // 從輸入框送的才清掉輸入框；按建議、按「再試一次」送的，不動使用者正在打的字
    if (text === input.value) input.value = ''
    typing.value = true
    typingSince = now.value = Date.now()
    try {
      const answer = await talkToAssistant(said, conversationId)
      conversationId = answer.conversationId
      saveConversationId(conversationId)
      messages.value.push(say('ai', answer.reply, answer.proposals.map((draft) => ({ draft, status: 'pending' }))))
    } catch (e) {
      // 登入過期：App 馬上換成登入頁、這段對話跟著被拆掉。把這句放回草稿，登入回來時還在輸入框裡
      if (e instanceof UnauthorizedError) {
        if (!input.value) saveDraft(draft, said)
        return
      }
      const failure = describeAssistantFailure(e)
      mine.failed = true
      const notice = say('ai', failure.text)
      notice.failure = { kind: failure.kind, canRetry: failure.canRetry }
      messages.value.push(notice)
      if (failure.waitSeconds > 0) {
        now.value = Date.now()
        retryAt.value = now.value + failure.waitSeconds * 1000
      }
      // 原封不動再送沒用的（400）：放回輸入框讓使用者改。輸入框裡已經有新打的字就不蓋掉
      if (!failure.canRetry && !input.value) input.value = said
    } finally {
      typing.value = false
    }
  }

  /** 「再試一次」：拿掉沒送出的那句和說明，原封不動再送。只有最後一則說明有這顆鈕，不會把舊的問題插到新對話中間 */
  function retry(notice: ChatMessage) {
    if (!canRetry(notice) || typing.value || waitSeconds.value > 0) return
    // 說明一定緊跟在沒送出的那句後面（等回覆時送不出別的）
    const at = messages.value.length - 1
    const said = messages.value[at - 1].text
    messages.value.splice(at - 1, 2)
    // send 自己接住所有錯誤（變成對話裡的說明），這裡不必等它
    void send(said)
  }

  /** 這則說明的「再試一次」要不要出現：要可以重送，而且是最後一則 */
  const canRetry = (msg: ChatMessage) => !!msg.failure?.canRetry && messages.value[messages.value.length - 1] === msg

  /** 把這則訊息裡還沒處理的草稿一次存起來（後端是全部存或全部不存）。 */
  async function confirm(msg: ChatMessage) {
    const pending = msg.proposals.filter((p) => p.status === 'pending')
    if (pending.length === 0 || saving.value) return
    saving.value = true
    msg.error = null
    try {
      // 草稿裡帶著卡片的 proposalId，後端核對過才算採納
      await calendar.add(pending.map((p) => p.draft))
      for (const p of pending) p.status = 'added'
    } catch (e) {
      msg.error = message(e)
    } finally {
      saving.value = false
    }
  }

  function cancel(msg: ChatMessage) {
    const pending = msg.proposals.filter((p) => p.status === 'pending')
    for (const p of pending) p.status = 'removed'
    reportDismissed(pending)
  }

  function drop(proposal: Proposal) {
    if (proposal.status !== 'pending') return
    proposal.status = 'removed'
    reportDismissed([proposal])
  }

  // 拒絕了哪幾張告訴後端（產品指標的採納率）。不等它、失敗也不說：使用者按的是「取消」，不該因為指標送不出去看到錯誤
  function reportDismissed(proposals: Proposal[]) {
    const ids = proposals.map((p) => p.draft.proposalId).filter((id): id is string => !!id)
    if (ids.length > 0) dismissProposals(ids).catch(() => {})
  }

  /** 帶著這筆草稿打開新增表單；在表單裡存好了，這張卡片就算「已加入」。 */
  function edit(proposal: Proposal) {
    // 草稿裡帶著卡片的 proposalId，表單存檔時會一起送出去，改過再存也算採納
    calendar.openForm(proposal.draft, () => {
      proposal.status = 'added'
    })
  }

  return { messages, input, typing, saving, waitSeconds, waitedSeconds, send, retry, canRetry, confirm, cancel, drop, edit, clear }
}

export type AssistantState = ReturnType<typeof useAssistant>

export const ASSISTANT: InjectionKey<AssistantState> = Symbol('assistant')

export function useAssistantState(): AssistantState {
  const state = inject(ASSISTANT)
  if (!state) throw new Error('AI 助理元件必須放在 CalendarView 底下')
  return state
}
