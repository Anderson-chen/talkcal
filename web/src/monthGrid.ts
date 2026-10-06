// 月曆格子的日期運算。全部是純函式、不碰畫面，CalendarView 只負責把結果畫出來。
//
// 日期一律用 'YYYY-MM-DD' 字串當 key，跟後端 LocalDate 的格式一樣。
// 需要算「下一天」時才暫時建 Date，而且只用本地時間的建構子（new Date(y, m, d)）和 getter（getFullYear…），
// 絕不用 toISOString()：它會先換成 UTC —— 在台灣，早上八點以前呼叫，日期就變成前一天。

/** 一頁月曆是固定的 6 週 × 7 天，不管這個月幾天、從星期幾開始，格子數都一樣，版面才不會跳。 */
export const GRID_DAYS = 42

/** 本地時間的 Date → 'YYYY-MM-DD'。 */
export function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 'YYYY-MM-DD' → 本地時間那天的 00:00。 */
function fromIsoDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function addDays(iso: string, days: number): string {
  const date = fromIsoDate(iso)
  date.setDate(date.getDate() + days)
  return toIsoDate(date)
}

/**
 * 某年某月（month 從 1 起算）那一頁月曆的 42 天。
 * 一週從星期一開始，跟後端的日期表（ExtractionRequest）一致：使用者說的「這週」在兩邊是同一週。
 */
export function monthGrid(year: number, month: number): string[] {
  const first = new Date(year, month - 1, 1)
  // getDay()：星期日是 0。換成「星期一是 0」，算出這個月一號前面要補幾天上個月
  const leading = (first.getDay() + 6) % 7
  const start = toIsoDate(new Date(year, month - 1, 1 - leading))
  return Array.from({ length: GRID_DAYS }, (_, i) => addDays(start, i))
}

/** 月曆一頁要向後端要的期間：第一格那天（含）到最後一格的隔天（不含）。 */
export function gridRange(days: string[]): { from: string; to: string } {
  return { from: days[0], to: addDays(days[days.length - 1], 1) }
}

/**
 * 一筆行程要畫在哪幾天的格子裡：從開始那天，到結束那天。
 * 跟後端「結束不含」同一套規則：剛好在 00:00 結束的行程，不算進結束那天
 * （22:00 到隔天 00:00 的行程只佔一格，不會在隔天冒出一個零長度的殘影）。
 */
export function daysCovered(start: string, end: string): string[] {
  const first = start.slice(0, 10)
  let last = end.slice(0, 10)
  if (end.slice(11, 16) === '00:00' && last > first) {
    last = addDays(last, -1)
  }
  const days: string[] = []
  // 'YYYY-MM-DD' 字串可以直接比大小：位數固定、由大單位排到小單位，字典序就是時間順序
  for (let day = first; day <= last; day = addDays(day, 1)) {
    days.push(day)
  }
  return days
}

const WEEKDAY_NAMES = ['一', '二', '三', '四', '五', '六', '日']

/** 星期幾的中文（「一」…「日」）。一週從星期一開始，跟 monthGrid 一致。 */
export function weekdayName(iso: string): string {
  return WEEKDAY_NAMES[(fromIsoDate(iso).getDay() + 6) % 7]
}

/** 這一天所在那一週的七天（星期一到星期日）。 */
export function weekOf(iso: string): string[] {
  const offset = (fromIsoDate(iso).getDay() + 6) % 7
  return Array.from({ length: 7 }, (_, i) => addDays(iso, i - offset))
}

/** 「10月6日」 */
export function monthDayLabel(iso: string): string {
  return `${Number(iso.slice(5, 7))}月${Number(iso.slice(8, 10))}日`
}

/** 「10月6日（週二）」：AI 卡片、詳情這種一行講完的地方用 */
export function dayLabel(iso: string): string {
  return `${monthDayLabel(iso)}（週${weekdayName(iso)}）`
}

/** 'YYYY-MM-DDTHH:mm[:ss]' → 'HH:mm' */
export function timeOf(dateTime: string): string {
  return dateTime.slice(11, 16)
}

