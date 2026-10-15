// monthGrid.ts 的單元測試。全部是純函式：給一樣的輸入就一定得到一樣的輸出，不碰畫面、不碰網路，所以最好測。
//
// 結構：
//   describe —— 把同一個函式的測試放一組，報告裡一層一層顯示
//   it       —— 一個測試，名字寫成一句話，讀起來就是規格：「它應該……」
//   expect   —— 斷言：實際值跟預期不一樣，這個測試就失敗，訊息會列出兩邊的值
import { describe, expect, it } from 'vitest'
import { daysCovered, gridRange, monthGrid, toIsoDate, weekdayName } from './monthGrid'

describe('toIsoDate', () => {
  it('月和日不足兩位要補 0，跟後端 LocalDate 的格式一樣', () => {
    // new Date(年, 月, 日) 的月是從 0 起算：8 是九月
    expect(toIsoDate(new Date(2026, 8, 5))).toBe('2026-09-05')
  })
})

describe('monthGrid', () => {
  // 2026 年 10 月 1 日是星期四。一週從星期一開始，所以第一格要往前補到 9/28（一）
  const october = monthGrid(2026, 10)

  it('一頁固定 42 格（6 週）：不管這個月幾天，版面都不會跳', () => {
    expect(october).toHaveLength(42)
  })

  it('第一格是那一週的星期一，最後一格是第六週的星期日', () => {
    expect(october[0]).toBe('2026-09-28')
    expect(weekdayName(october[0])).toBe('一')
    expect(october[41]).toBe('2026-11-08')
    expect(weekdayName(october[41])).toBe('日')
  })

  it('跟後端要資料的期間：最後一格的隔天「不含」', () => {
    expect(gridRange(october)).toEqual({ from: '2026-09-28', to: '2026-11-09' })
  })
})

describe('daysCovered', () => {
  it('跨夜的行程要畫在兩天', () => {
    expect(daysCovered('2026-10-06T22:00', '2026-10-07T01:00')).toEqual(['2026-10-06', '2026-10-07'])
  })

  it('剛好在隔天 00:00 結束的不算進隔天：結束時間「不含」，跟後端同一套規則', () => {
    expect(daysCovered('2026-10-06T22:00', '2026-10-07T00:00')).toEqual(['2026-10-06'])
  })
})
