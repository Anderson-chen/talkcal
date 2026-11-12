// 封存（#274）：結束超過 2 年的行程，後端每天會搬到封存，月曆上就看不到了，只能照日期查看。
// 這裡是純函式：哪幾個月要註明「已自動封存」、查詢的日期合不合規、查到的行程時間怎麼寫。
// 畫面在 ArchiveNotice.vue（說明和入口）、ArchiveViewer.vue（查詢和結果）。

import { dayLabel, timeOf, toIsoDate } from '../monthGrid'

/** 結束超過幾年就封存。跟後端的 calendar.event.retention 一致：畫面上寫著這個數字，改那邊要一起改這裡 */
export const ARCHIVE_YEARS = 2

/** 畫面上的說明，月曆上的提示和查詢畫面共用 */
export const ARCHIVE_NOTICE = `結束超過 ${ARCHIVE_YEARS} 年的行程已自動封存，只能查看`

/**
 * 今天往前推 2 年的那一天：比它早結束的行程都已經封存了。
 * 2/29 往前推到沒有 2/29 的那年算 2/28，跟後端 LocalDateTime.minus(Period) 一樣。
 */
export function archiveCutoff(today: string): string {
  const year = Number(today.slice(0, 4)) - ARCHIVE_YEARS
  const month = Number(today.slice(5, 7))
  const day = Math.min(Number(today.slice(8, 10)), lastDayOf(year, month))
  return toIsoDate(new Date(year, month - 1, day))
}

/** 這個月有沒有已經封存的日子：月初比分界早，就有一部分（或全部）行程已經搬走了 */
export function isArchivedMonth(year: number, month: number, today: string): boolean {
  return monthBounds(year, month).from < archiveCutoff(today)
}

/** 這個月的第一天和最後一天（兩端都含），查詢畫面預設查整個月 */
export function monthBounds(year: number, month: number): { from: string; to: string } {
  return { from: toIsoDate(new Date(year, month - 1, 1)), to: toIsoDate(new Date(year, month - 1, lastDayOf(year, month))) }
}

// new Date(y, m, 0)：Date 的月份從 0 起算，所以這是「m 月的下個月的第 0 天」，也就是 m 月的最後一天
function lastDayOf(year: number, month: number): number {
  return new Date(year, month, 0).getDate()
}

/** 查詢的日期哪裡不對；沒問題回 null。超過上限（24 個月）由後端擋，它回的話直接顯示 */
export function rangeProblem(from: string, to: string): string | null {
  if (!from || !to) return '請選日期'
  // 'YYYY-MM-DD' 字串直接比大小就是時間順序
  if (from > to) return '結束日期不能早於開始日期'
  return null
}

/**
 * 查到的行程什麼時候：封存的都是兩年以前的，所以帶年份。
 * 同一天：「2024年3月5日（週二） 09:00 – 10:30」；跨天：「2024年3月30日（週六） 09:00 – 4月2日（週二） 18:00」
 */
export function archivedTimeLabel(start: string, end: string): string {
  const startDay = start.slice(0, 10)
  const endDay = end.slice(0, 10)
  const from = `${withYear(startDay)} ${timeOf(start)}`
  if (endDay === startDay) return `${from} – ${timeOf(end)}`
  const endLabel = endDay.slice(0, 4) === startDay.slice(0, 4) ? dayLabel(endDay) : withYear(endDay)
  return `${from} – ${endLabel} ${timeOf(end)}`
}

function withYear(day: string): string {
  return `${Number(day.slice(0, 4))}年${dayLabel(day)}`
}
