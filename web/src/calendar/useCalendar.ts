// 行事曆的狀態與動作。桌面版和手機版共用 CalendarView 建的這一份：
// 視窗跨過寬度分界、整個版面換掉時，選的日期、看的月份還在。
//
// guest：沒登入的訪客。版面照常，但不讀行程（沒有帳號就沒有行程，讀了也是 401），
// 新增、修改、刪除都換成帶去登入。

import { computed, inject, onScopeDispose, ref, watch, type InjectionKey } from 'vue'
import { addEvents, listEvents, NotFoundError, removeEvent, updateEvent, type Category, type EventFields, type SavedEvent } from '../calendar'
import { requestLogin } from '../auth'
import { message } from '../http'
import { addDays, daysCovered, gridRange, monthGrid, toIsoDate } from '../monthGrid'
import { isArchivedMonth } from './archive'

export type View = 'month' | 'week' | 'day'

/**
 * editing：改已經存好的那一筆，存檔送 PUT。expected 是改之前看到的樣子，跟著送出去；
 * 那筆在這之間被別人改過的話後端回 409，不會蓋掉別人的改動。
 */
export interface FormRequest {
  initial: EventFields
  editing?: { id: string; expected: EventFields }
  // AI 卡片在存好之後標成已加入、已修改
  onSaved?: (saved: SavedEvent) => void
}

export function useCalendar(guest = false) {
  // 瀏覽器的本地日期。在台灣跟後端的 calendar.zone 是同一天，在別的時區會差，是已知的限制
  const today = toIsoDate(new Date())

  const sel = ref(today)
  const view = ref<View>('month')
  // 載入的是哪個月的月曆頁。週、日視圖也只讀這一頁：任何一天所在的那一週，一定落在這個月的 42 格裡
  const shown = ref(monthOf(today))
  const events = ref<SavedEvent[]>([])
  const loadError = ref<string | null>(null)
  const hidden = ref<Partial<Record<Category, boolean>>>({})
  const detailId = ref<string | null>(null)
  const form = ref<FormRequest | null>(null)

  const days = computed(() => monthGrid(shown.value.year, shown.value.month))

  // ── 載入 ──────────────────────────────────────────────
  // 月份沒變也要重讀時（新增、刪除之後）把 version 加一
  const version = ref(0)
  let latestLoad = 0

  watch([days, version], async () => {
    // 快速連按下個月時回應可能不照順序回來，不是最新那一號就丟掉
    if (guest) return
    const ticket = ++latestLoad
    const { from, to } = gridRange(days.value)
    try {
      const loaded = await listEvents(from, to)
      if (ticket !== latestLoad) return
      events.value = loaded
      loadError.value = null
    } catch (e) {
      if (ticket !== latestLoad) return
      loadError.value = message(e)
    }
  }, { immediate: true })

  const reload = () => version.value++

  // 讀失敗之後網路回來了就自己重讀，不必等使用者按「重新讀取」
  const reloadIfFailed = () => {
    if (loadError.value) reload()
  }
  window.addEventListener('online', reloadIfFailed)
  onScopeDispose(() => window.removeEventListener('online', reloadIfFailed))

  // ── 查詢 ──────────────────────────────────────────────
  const visible = computed(() => events.value.filter((e) => !hidden.value[e.category]))

  // 跨天的行程會出現在涵蓋的每一天
  const byDay = computed(() => {
    const map = new Map<string, SavedEvent[]>()
    for (const event of visible.value) {
      for (const day of daysCovered(event.start, event.end)) {
        const list = map.get(day) ?? []
        list.push(event)
        map.set(day, list)
      }
    }
    return map
  })

  const eventsOn = (day: string) => byDay.value.get(day) ?? []

  // 側欄篩選旁邊的數字：這個月開始的行程各分類幾筆，被篩掉的也算
  const monthCounts = computed(() => {
    const prefix = `${shown.value.year}-${String(shown.value.month).padStart(2, '0')}`
    const counts: Record<Category, number> = { WORK: 0, PERSONAL: 0, HEALTH: 0, SOCIAL: 0 }
    for (const e of events.value) if (e.start.startsWith(prefix)) counts[e.category]++
    return counts
  })

  const detail = computed(() => events.value.find((e) => e.id === detailId.value) ?? null)

  const archived = computed(() => isArchivedMonth(shown.value.year, shown.value.month, today))

  // ── 導覽 ──────────────────────────────────────────────
  function pickDate(day: string, nextView?: View) {
    sel.value = day
    const month = monthOf(day)
    if (month.year !== shown.value.year || month.month !== shown.value.month) shown.value = month
    if (nextView) view.value = nextView
  }

  function shiftMonth(n: number) {
    // 月份 13、0 交給 Date 進位成明年一月、去年十二月
    const first = new Date(shown.value.year, shown.value.month - 1 + n, 1)
    shown.value = { year: first.getFullYear(), month: first.getMonth() + 1 }
  }

  function step(direction: 1 | -1) {
    if (view.value === 'month') shiftMonth(direction)
    else pickDate(addDays(sel.value, direction * (view.value === 'week' ? 7 : 1)))
  }

  function toggleCategory(category: Category) {
    hidden.value = { ...hidden.value, [category]: !hidden.value[category] }
  }

  // ── 新增、修改、刪除 ─────────────────────────────────────────
  /** idempotencyKey 由呼叫端決定：只有呼叫端能判斷再按一次是不是同一次新增 */
  // 訪客按到要存檔的功能就帶去登入；丟例外讓呼叫端（助理卡片）維持原狀，不顯示成已加入
  function needLogin(reason: string): never {
    requestLogin(reason)
    throw new Error(reason)
  }

  async function add(drafts: EventFields[], idempotencyKey?: string): Promise<SavedEvent[]> {
    if (guest) needLogin('登入後，助理的卡片就能直接加進行事曆')
    const saved = await addEvents(drafts, idempotencyKey)
    showSaved(saved)
    return saved
  }

  // 存進去的分類被篩掉的話就重新顯示，不然使用者會以為沒存到
  function showSaved(saved: SavedEvent[]) {
    const shownAgain = { ...hidden.value }
    for (const e of saved) delete shownAgain[e.category]
    hidden.value = shownAgain
    if (saved.length) pickDate(saved[0].start.slice(0, 10))
    reload()
  }

  async function update(id: string, expected: EventFields, event: EventFields): Promise<SavedEvent> {
    if (guest) needLogin('登入後就能修改行程')
    const saved = await updateEvent(id, expected, event)
    showSaved([saved])
    return saved
  }

  /** 已經不在（別的分頁先刪了）也算成功：使用者要的結果就是這筆不在 */
  async function remove(id: string) {
    if (guest) needLogin('登入後就能刪除行程')
    try {
      await removeEvent(id)
    } catch (e) {
      if (!(e instanceof NotFoundError)) throw e
    }
    if (detailId.value === id) detailId.value = null
    reload()
  }

  function openForm(initial?: Partial<EventFields>, onSaved?: (saved: SavedEvent) => void) {
    if (guest) {
      requestLogin('登入後就能新增行程')
      return
    }
    detailId.value = null
    form.value = { initial: { ...blankDraft(sel.value), ...initial }, onSaved }
  }

  /** initial 不給就用這筆現在的樣子，助理的修改卡片會給要改成的樣子。expected 見 FormRequest */
  function openEdit(id: string, expected: EventFields, initial: EventFields = expected, onSaved?: (saved: SavedEvent) => void) {
    if (guest) {
      requestLogin('登入後就能修改行程')
      return
    }
    detailId.value = null
    form.value = { initial: { ...initial }, editing: { id, expected }, onSaved }
  }

  return {
    guest, today, sel, view, shown, days, events, loadError, hidden, detailId, detail, form, monthCounts, archived,
    eventsOn, pickDate, shiftMonth, step, toggleCategory, add, update, remove, openForm, openEdit, reload,
  }
}

export type CalendarState = ReturnType<typeof useCalendar>

export const CALENDAR: InjectionKey<CalendarState> = Symbol('calendar')

/** 沒被 CalendarView 包住就直接丟例外，比拿到 undefined 好查 */
export function useCalendarState(): CalendarState {
  const state = inject(CALENDAR)
  if (!state) throw new Error('行事曆元件必須放在 CalendarView 底下')
  return state
}

/** 選的那天 10:00–11:00、工作，照設計稿 */
export function blankDraft(day: string): EventFields {
  return { title: '', start: `${day}T10:00`, end: `${day}T11:00`, category: 'WORK', location: null, note: null }
}

export function monthOf(day: string) {
  return { year: Number(day.slice(0, 4)), month: Number(day.slice(5, 7)) }
}

export { message }
