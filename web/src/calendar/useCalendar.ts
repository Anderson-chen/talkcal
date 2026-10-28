// 行事曆的狀態與動作：選了哪天、看哪種視圖、這一頁有哪些行程、篩掉哪些分類、新增、刪除。
//
// 桌面版和手機版共用這一份：兩種版面只是把同樣的狀態畫成不同樣子。
// 由 CalendarView 建一份、provide 給底下所有元件，元件之間不必一層一層傳 props。
// 螢幕寬度跨過分界、版面整個換掉時，狀態也還在（選的日期、看的月份不會跳回今天）。

import { computed, inject, ref, watch, type InjectionKey } from 'vue'
import { addEvents, listEvents, NotFoundError, removeEvent, type Category, type EventFields, type SavedEvent } from '../calendar'
import { addDays, daysCovered, gridRange, monthGrid, toIsoDate } from '../monthGrid'

export type View = 'month' | 'week' | 'day'

/** 新增表單的初始值：手動新增是空白的，從 AI 卡片「修改」過來的帶著草稿。 */
export interface FormRequest {
  initial: EventFields
  // 存好之後要通知誰（AI 卡片要把那一筆標成「已加入」）
  onSaved?: (saved: SavedEvent) => void
}

export function useCalendar() {
  // 「今天」取瀏覽器的本地日期。跟後端的 calendar.zone（台北）在台灣是同一天；不在台灣用的話會差，那是已知的限制
  const today = toIsoDate(new Date())

  const sel = ref(today)
  const view = ref<View>('month')
  // 現在載入的是哪個月的月曆頁。週、日視圖也靠它：任何一天所在的那一週，一定落在它那個月的 42 格裡
  const shown = ref(monthOf(today))
  const events = ref<SavedEvent[]>([])
  const loadError = ref<string | null>(null)
  const hidden = ref<Partial<Record<Category, boolean>>>({})
  const detailId = ref<string | null>(null)
  const form = ref<FormRequest | null>(null)

  const days = computed(() => monthGrid(shown.value.year, shown.value.month))

  // ── 載入 ──────────────────────────────────────────────
  // 換月份、或資料變了（新增、刪除）就重讀這一頁。version 只是用來「明明月份沒變也要重讀」的開關
  const version = ref(0)
  let latestLoad = 0

  watch([days, version], async () => {
    // 快速連按「下個月」時，回應可能不照順序回來：每次領一個號碼，回來時不是最新那一號就丟掉
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

  // ── 查詢 ──────────────────────────────────────────────
  const visible = computed(() => events.value.filter((e) => !hidden.value[e.category]))

  // 每一天有哪些行程（跨夜、跨天的會出現在它涵蓋的每一天），依開始時間排好
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

  // 側欄篩選旁邊的數字：這個月開始的行程，各分類幾筆（不管有沒有被篩掉）
  const monthCounts = computed(() => {
    const prefix = `${shown.value.year}-${String(shown.value.month).padStart(2, '0')}`
    const counts: Record<Category, number> = { WORK: 0, PERSONAL: 0, HEALTH: 0, SOCIAL: 0 }
    for (const e of events.value) if (e.start.startsWith(prefix)) counts[e.category]++
    return counts
  })

  const detail = computed(() => events.value.find((e) => e.id === detailId.value) ?? null)

  // ── 導覽 ──────────────────────────────────────────────
  function pickDate(day: string, nextView?: View) {
    sel.value = day
    const month = monthOf(day)
    if (month.year !== shown.value.year || month.month !== shown.value.month) shown.value = month
    if (nextView) view.value = nextView
  }

  function shiftMonth(n: number) {
    // 交給 Date 處理進位：月份 13 會變成明年一月、0 變成去年十二月
    const first = new Date(shown.value.year, shown.value.month - 1 + n, 1)
    shown.value = { year: first.getFullYear(), month: first.getMonth() + 1 }
  }

  /** 「上一頁／下一頁」：月視圖跳一個月，週視圖跳七天，日視圖跳一天 */
  function step(direction: 1 | -1) {
    if (view.value === 'month') shiftMonth(direction)
    else pickDate(addDays(sel.value, direction * (view.value === 'week' ? 7 : 1)))
  }

  function toggleCategory(category: Category) {
    hidden.value = { ...hidden.value, [category]: !hidden.value[category] }
  }

  // ── 新增、刪除 ─────────────────────────────────────────
  /** 存起來、跳到第一筆那天、重讀這一頁。存進去的分類如果被篩掉了，打開它 —— 不然使用者會以為沒存到。 */
  async function add(drafts: EventFields[]): Promise<SavedEvent[]> {
    const saved = await addEvents(drafts)
    const shownAgain = { ...hidden.value }
    for (const e of saved) delete shownAgain[e.category]
    hidden.value = shownAgain
    if (saved.length) pickDate(saved[0].start.slice(0, 10))
    reload()
    return saved
  }

  /** 刪掉。已經不在了（別的分頁先刪了）也當成成功：使用者要的結果本來就是「它不在」，重讀一次讓畫面跟上 */
  async function remove(id: string) {
    try {
      await removeEvent(id)
    } catch (e) {
      if (!(e instanceof NotFoundError)) throw e
    }
    if (detailId.value === id) detailId.value = null
    reload()
  }

  function openForm(initial?: Partial<EventFields>, onSaved?: (saved: SavedEvent) => void) {
    detailId.value = null
    form.value = { initial: { ...blankDraft(sel.value), ...initial }, onSaved }
  }

  return {
    today, sel, view, shown, days, events, loadError, hidden, detailId, detail, form, monthCounts,
    eventsOn, pickDate, shiftMonth, step, toggleCategory, add, remove, openForm, reload,
  }
}

export type CalendarState = ReturnType<typeof useCalendar>

export const CALENDAR: InjectionKey<CalendarState> = Symbol('calendar')

/** 元件取得共用狀態。沒被 CalendarView 包住就是接線錯誤，直接炸比拿到 undefined 好除錯 */
export function useCalendarState(): CalendarState {
  const state = inject(CALENDAR)
  if (!state) throw new Error('行事曆元件必須放在 CalendarView 底下')
  return state
}

/** 手動新增的預設值：選的那天 10:00–11:00、工作（照設計稿） */
export function blankDraft(day: string): EventFields {
  return { title: '', start: `${day}T10:00`, end: `${day}T11:00`, category: 'WORK', location: null, note: null }
}

export function monthOf(day: string) {
  return { year: Number(day.slice(0, 4)), month: Number(day.slice(5, 7)) }
}

export function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
