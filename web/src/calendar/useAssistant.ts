// AI 助理的對話狀態（POST /api/calendar/assistant）。
//
// 助理提議的新增、修改、刪除都先變成卡片，使用者按了確認才送 /events。
// 新增卡片可以整則一起加入；修改、刪除卡片要一張一張確認，因為改錯一筆比漏加一筆難收拾。
//
// 對話的記憶在後端，這裡只記 conversationId，重新整理後用這個 id 讀回對話的文字。
// 「清空」只丟掉這個 id，後端那段不刪，留給後端的清理排程。
// 讀回來的舊訊息不帶卡片：後端沒記卡片後來加入了還是取消了，顯示出來可能已經過時。
//
// 回不了的時候（每一種顯示什麼見 assistantFailure.ts）：
// - 使用者那句留在對話裡、標成「沒送出」，附「再試一次」，不必重打
// - 後端給了要等幾秒就倒數，倒數完之前送不出去，輸入框照樣能打字
// - 模型最久可能 2 分鐘才回應，等太久時「思考中」會顯示已經等了幾秒，讓使用者知道請求還在處理
// - 還沒送出的字放 sessionStorage：登入過期跳回登入頁、或重新整理，回來時還在
//
// 沒登入的訪客（calendar.guest）走同一條路，只是鎖住：輸入框停用，只能按 SUGGESTIONS，
// 後端回預先寫好的答案、沒有 conversationId；卡片的加入、修改由 useCalendar 帶去登入；每天的次數用完時改成請訪客註冊。

import { computed, inject, onScopeDispose, ref, watch, type InjectionKey } from 'vue'
import { me, requestLogin } from '../auth'
import { assistantHistory, dismissProposals, talkToAssistant, type AssistantChange, type EventFields, type SavedEvent } from '../calendar'
import { UnauthorizedError } from '../http'
import { describeAssistantFailure, type FailureKind } from './assistantFailure'
import { oneAtATime, retryKey, type RetryKey } from './submitOnce'
import { message, type CalendarState } from './useCalendar'

export type ProposalStatus = 'pending' | 'added' | 'removed'

export interface Proposal {
  draft: EventFields
  status: ProposalStatus
}

export type ActionStatus = 'pending' | 'done' | 'dismissed'

interface ActionCard {
  status: ActionStatus
  // 送出失敗的原因，例如 409：卡片出現之後那筆被改過
  error: string | null
}

export interface ChangeCard extends ActionCard {
  change: AssistantChange
}

export interface RemovalCard extends ActionCard {
  event: SavedEvent
}

export interface ChatMessage {
  id: number
  role: 'me' | 'ai'
  text: string
  proposals: Proposal[]
  changes: ChangeCard[]
  removals: RemovalCard[]
  // 加入行事曆失敗的原因；草稿留著讓使用者再按一次
  error: string | null
  // 使用者這句沒送成功
  failed?: boolean
  // 助理回不了的說明。canRetry：原封不動再送有沒有意義，400 沒有，要先改
  failure?: { kind: FailureKind; canRetry: boolean }
  // 訪客看的示範：回答下面的小字（還能試幾次）；次數用完時顯示註冊按鈕
  hint?: string
  signUp?: boolean
}

/** 「思考中」超過這麼多秒就改成顯示已經等了多久。助理平常 p95 約 3 秒，等到 10 秒已經明顯不正常 */
export const SLOW_AFTER_SECONDS = 10

// 三種能做的事各舉一個例子：新增、查詢、找空檔。
// 沒登入的示範（GuestDemo.vue）也送這幾句，後端的 GuestDemo 只認得一字不差的這幾句，改了要一起改
export const SUGGESTIONS = ['明天下午3點和 Amy 開會', '明天有什麼行程？', '這週哪天下午有空？']

const GREETING = '嗨！我是你的行事曆助理。可以幫你新增、修改、刪除行程，查某天的安排，或找出空檔。不管哪一種，我都會整理成卡片讓你確認後才動行事曆。'
const GUEST_GREETING = '嗨！我是你的行事曆助理。還沒登入也能先點下面的問題，看我怎麼回答（每天 3 次）；登入後就能直接打字問我。'
/** 訪客的輸入框停用，placeholder 說明怎麼才能打字 */
export const GUEST_PLACEHOLDER = '登入後開始使用'

// 私密視窗、被封鎖的網站資料會讓 localStorage 丟例外，下面一律捕捉後忽略，最壞就是重新整理後開新對話。
// key 帶登入者的 id，換人登入才不會讀回上一個人的對話。擋住讀別人對話的是後端的授權，這裡只是不主動去要
const storageKey = () => `talkcal.calendar.assistant.conversation.${me.value?.id ?? 'anonymous'}`
// key 在建立助理時算一次就好：登入過期時 401 的通知比例外早到，me 已經是 null，那時才算會存到 anonymous 的 key
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
    // 存不了只是登入過期、重新整理後拿不回草稿
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
    // 存不了只是重新整理後接不回這段對話
  }
}

export function useAssistant(calendar: CalendarState) {
  const guest = calendar.guest
  const greeting = guest ? GUEST_GREETING : GREETING
  let nextId = 1
  const say = (role: ChatMessage['role'], text: string, proposals: Proposal[] = []): ChatMessage =>
    ({ id: nextId++, role, text, proposals, changes: [], removals: [], error: null })

  const messages = ref<ChatMessage[]>([say('ai', greeting)])
  const draft = draftKey(me.value?.id)
  const input = ref(loadDraft(draft))
  // 送出去、還沒回成功的那一句：輸入框清掉了，草稿裡還留著這句，送到一半重新整理或登入過期也拿得回來
  let unsent = ''
  const keepDraft = () => saveDraft(draft, input.value || unsent)
  watch(input, keepDraft)
  const typing = ref(false)
  const saving = ref(false)
  // 訪客今天的示範次數用完了
  const guestLimit = ref(false)

  // 倒數和「已經等了幾秒」都看 now。只在倒數或等回覆時每秒更新，平常不觸發重畫
  const now = ref(Date.now())
  const retryAt = ref(0)
  let typingSince = 0
  const timer = setInterval(() => {
    if (typing.value || retryAt.value > now.value) now.value = Date.now()
  }, 1000)
  onScopeDispose(() => clearInterval(timer))

  const waitSeconds = computed(() => Math.max(0, Math.ceil((retryAt.value - now.value) / 1000)))
  // 在等回覆或還在倒數時送了一定失敗，送出、建議說法、再試都停用
  const blocked = computed(() => typing.value || waitSeconds.value > 0 || guestLimit.value)
  const waitedSeconds = computed(() => (typing.value ? Math.max(0, Math.floor((now.value - typingSince) / 1000)) : 0))

  // 每則訊息各有一把冪等鍵：A 失敗後先去加 B，再回來重按 A，帶的還是 A 那把
  const addKeys = new Map<number, RetryKey>()
  let conversationId = loadConversationId()

  // 讀回時 typing 亮著：舊訊息回來前就送出新的一句，順序會亂
  async function restore() {
    if (!conversationId || guest) return
    typing.value = true
    typingSince = now.value = Date.now()
    try {
      const history = await assistantHistory(conversationId)
      if (history.messages.length === 0) {
        // 後端已經沒有這段（被清掉、資料庫重建），當成新對話
        conversationId = undefined
        saveConversationId(undefined)
        return
      }
      messages.value = [say('ai', greeting), ...history.messages.map((m) => say(m.role === 'user' ? 'me' : 'ai', m.text))]
      // 送到一半重新整理時，瀏覽器斷了請求，後端卻照樣做完、記進對話。讀回來已經有那句，就清掉草稿，免得又送一次
      const lastAsked = history.messages.filter((m) => m.role === 'user').at(-1)?.text
      if (lastAsked && lastAsked === input.value.trim()) input.value = ''
    } catch (e) {
      messages.value.push(say('ai', `讀不回上次的對話：${message(e)}。可以直接開始新的對話。`))
    } finally {
      typing.value = false
    }
  }
  // 不等 restore() 完成，讀回來之前先顯示招呼語；錯誤在 restore() 裡處理
  void restore()

  /** 丟掉 conversationId，下一句後端就開一段新的。不呼叫後端，所以不會清空失敗 */
  function clear() {
    if (typing.value) return
    conversationId = undefined
    saveConversationId(undefined)
    messages.value = [say('ai', greeting)]
    // 沒送成功的那句也丟掉，不然重新整理後又出現在輸入框
    unsent = ''
    input.value = ''
    keepDraft()
  }

  /** 沒給 text 是從輸入框送的；按建議、按「再試一次」會給 text */
  async function send(text?: string) {
    const fromInput = text === undefined
    const said = (text ?? input.value).trim()
    if (!said || blocked.value || (guest && fromInput)) return
    // 從陣列拿回來的才是 reactive proxy，之後改 failed 畫面才會跟著變
    const mine = messages.value[messages.value.push(say('me', said)) - 1]
    unsent = said
    // 按建議、按「再試一次」送的不動輸入框：使用者可能正在打別的
    if (fromInput) input.value = ''
    typing.value = true
    typingSince = now.value = Date.now()
    try {
      const answer = await talkToAssistant(said, conversationId)
      // 登入過期時後端把這個請求當成訪客：固定問題拿到的是訪客的回答。照 401 處理，那句留在草稿裡
      if (!guest && answer.guestRemaining !== undefined) {
        me.value = null
        requestLogin()
        return
      }
      conversationId = answer.conversationId ?? undefined
      saveConversationId(conversationId)
      const reply = say('ai', answer.reply, answer.proposals.map((draft) => ({ draft, status: 'pending' })))
      if (answer.guestRemaining !== undefined) reply.hint = `今天還能試 ${answer.guestRemaining} 次`
      reply.changes = answer.changes.map((change) => ({ change, status: 'pending', error: null }))
      reply.removals = answer.removals.map((event) => ({ event, status: 'pending', error: null }))
      messages.value.push(reply)
      unsent = ''
      keepDraft()
    } catch (e) {
      // 登入過期時 App 會換成登入頁、拆掉這段對話；那句還在草稿裡，登入回來時會在輸入框
      if (e instanceof UnauthorizedError) return
      const failure = describeAssistantFailure(e)
      mine.failed = true
      const notice = say('ai', failure.text)
      notice.failure = { kind: failure.kind, canRetry: failure.canRetry }
      if (failure.kind === 'guest-limit') {
        notice.signUp = true
        guestLimit.value = true
      }
      messages.value.push(notice)
      if (failure.waitSeconds > 0) {
        now.value = Date.now()
        retryAt.value = now.value + failure.waitSeconds * 1000
      }
      // 原封不動再送沒用（400），放回輸入框讓使用者改；已經有新打的字就不蓋掉
      if (!failure.canRetry && !input.value) input.value = said
    } finally {
      typing.value = false
    }
  }

  function signUp() {
    requestLogin('註冊之後就能直接跟助理說話，不限這幾題', 'register')
  }

  /** 拿掉沒送出的那句和說明，原封不動再送。只有最後一則說明能再試，舊的問題才不會插到新對話中間 */
  function retry(notice: ChatMessage) {
    if (!canRetry(notice) || blocked.value) return
    // 說明一定緊跟在沒送出的那句後面：等回覆時送不出別的
    const at = messages.value.length - 1
    const said = messages.value[at - 1].text
    messages.value.splice(at - 1, 2)
    // send 會處理所有錯誤，不必等待
    void send(said)
  }

  const canRetry = (msg: ChatMessage) => !!msg.failure?.canRetry && messages.value[messages.value.length - 1] === msg

  /**
   * 這則訊息裡還沒處理的草稿一次存起來，後端全部存或全部不存。
   * 失敗後再按帶同一把冪等鍵，上次其實存好了也不會多一份。
   */
  const confirm = oneAtATime(saving, async (msg: ChatMessage) => {
    const pending = msg.proposals.filter((p) => p.status === 'pending')
    if (pending.length === 0) return
    msg.error = null
    const drafts = pending.map((p) => p.draft)
    const keys = addKeys.get(msg.id) ?? retryKey()
    addKeys.set(msg.id, keys)
    try {
      // 草稿帶著卡片的 proposalId，後端核對過才算採納
      await calendar.add(drafts, keys.for(drafts))
      for (const p of pending) p.status = 'added'
      addKeys.delete(msg.id)
    } catch (e) {
      msg.error = message(e)
    }
  })

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

  // 拒絕了哪幾張告訴後端，算採納率用。不等待、失敗也不顯示：使用者按的是取消，不該因為指標送不出去看到錯誤
  function reportDismissed(proposals: Proposal[]) {
    const ids = proposals.map((p) => p.draft.proposalId).filter((id): id is string => !!id)
    if (ids.length > 0 && !guest) dismissProposals(ids).catch(() => {})
  }

  /** 帶著草稿打開新增表單，在表單裡存好了這張卡片就算加入 */
  function edit(proposal: Proposal) {
    // proposalId 跟著草稿進表單、存檔時一起送，改過再存也算採納
    calendar.openForm(proposal.draft, () => {
      proposal.status = 'added'
    })
  }

  // ── 修改、刪除卡片 ──────────────────────────────
  // 跟加入行事曆共用 saving：同一時間只送一件事，畫面上的按鈕一起停用

  // 失敗不自動重試：修改失敗多半是卡片出現之後那筆被改過（409），重試等於拿舊的樣子再蓋一次
  async function act(card: ActionCard, action: () => Promise<unknown>) {
    if (card.status !== 'pending') return
    card.error = null
    try {
      await action()
      card.status = 'done'
    } catch (e) {
      card.error = message(e)
    }
  }

  const confirmChange = oneAtATime(saving, (card: ChangeCard) =>
    act(card, () => calendar.update(card.change.eventId, card.change.before, card.change.after)))

  /** 帶著要改成的樣子打開表單讓使用者自己調，存好了這張卡片就算完成 */
  function editChange(card: ChangeCard) {
    if (card.status !== 'pending') return
    calendar.openEdit(card.change.eventId, card.change.before, card.change.after, () => {
      card.status = 'done'
    })
  }

  /** 已經不在的也算刪好（見 useCalendar.ts 的 remove） */
  const confirmRemoval = oneAtATime(saving, (card: RemovalCard) => act(card, () => calendar.remove(card.event.id)))

  function dismiss(card: ActionCard) {
    if (card.status === 'pending') card.status = 'dismissed'
  }

  return {
    guest, messages, input, typing, saving, waitSeconds, blocked, waitedSeconds, send, retry, canRetry, confirm, cancel, drop, edit, clear, signUp,
    confirmChange, editChange, confirmRemoval, dismiss,
  }
}

export type AssistantState = ReturnType<typeof useAssistant>

export const ASSISTANT: InjectionKey<AssistantState> = Symbol('assistant')

export function useAssistantState(): AssistantState {
  const state = inject(ASSISTANT)
  if (!state) throw new Error('AI 助理元件必須放在 CalendarView 底下')
  return state
}
