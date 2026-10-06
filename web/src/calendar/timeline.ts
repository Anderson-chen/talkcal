// 週、日視圖的時間軸：哪些行程畫在這一天、畫在幾點到幾點、重疊時怎麼並排。
// 純函式，不碰畫面；元件只把結果換成 top / height / left / width。

import type { SavedEvent } from '../calendar'
import { addDays } from '../monthGrid'

/** 一筆行程在某一天的那一段：跨夜的行程在前後兩天各有一段。 */
export interface Segment {
  event: SavedEvent
  // 從那天 00:00 起算的分鐘數，0 ～ 1440
  startMin: number
  endMin: number
  // 這一段是不是行程的開頭／結尾：決定時間標籤要不要顯示「↳」
  startsToday: boolean
  endsToday: boolean
}

/** 排好位置的一段：col 是第幾欄、cols 是那一群重疊的行程總共分幾欄。 */
export interface Placed extends Segment {
  col: number
  cols: number
}

const DAY_MINUTES = 24 * 60

/** 'YYYY-MM-DDTHH:mm[:ss]' 的時分 → 分鐘數 */
function minutesOf(dateTime: string): number {
  return Number(dateTime.slice(11, 13)) * 60 + Number(dateTime.slice(14, 16))
}

/**
 * 把行程切到某一天：只留跟 [那天 00:00, 隔天 00:00) 重疊的部分。
 * 跟後端「有重疊就算、結束不含」同一套規則：剛好 00:00 結束的行程，不會在隔天留下零長度的一段。
 * 字串可以直接比大小：'YYYY-MM-DDTHH:mm' 位數固定、由大單位排到小單位，字典序就是時間順序。
 */
export function segmentsOn(day: string, events: SavedEvent[]): Segment[] {
  const dayStart = `${day}T00:00`
  const dayEnd = `${addDays(day, 1)}T00:00`
  const segments: Segment[] = []
  for (const event of events) {
    const start = event.start.slice(0, 16)
    const end = event.end.slice(0, 16)
    if (!(start < dayEnd && end > dayStart)) continue
    const startsToday = start >= dayStart
    const endsToday = end <= dayEnd
    segments.push({
      event,
      startMin: startsToday ? minutesOf(start) : 0,
      endMin: endsToday ? (end === dayEnd ? DAY_MINUTES : minutesOf(end)) : DAY_MINUTES,
      startsToday,
      endsToday,
    })
  }
  return segments.sort((a, b) => a.startMin - b.startMin || a.endMin - b.endMin)
}

/**
 * 重疊的行程並排（照設計稿的做法）：
 * 先把「接在一起有重疊」的行程分成一群；群裡每一筆放進第一個空出來的欄位；整群用同樣的欄數。
 * 這樣不重疊的行程佔滿整寬，只有真的撞在一起的才分欄。
 */
export function placeSegments(segments: Segment[]): Placed[] {
  const placed: Placed[] = []
  let group: Segment[] = []
  let groupEnd = -1

  const flush = () => {
    const columnEnds: number[] = []
    const inGroup = group.map((segment) => {
      let col = 0
      while (columnEnds[col] !== undefined && columnEnds[col] > segment.startMin) col++
      columnEnds[col] = segment.endMin
      return { segment, col }
    })
    for (const { segment, col } of inGroup) placed.push({ ...segment, col, cols: columnEnds.length })
    group = []
    groupEnd = -1
  }

  for (const segment of segments) {
    if (group.length && segment.startMin >= groupEnd) flush()
    group.push(segment)
    groupEnd = Math.max(groupEnd, segment.endMin)
  }
  if (group.length) flush()
  return placed
}
