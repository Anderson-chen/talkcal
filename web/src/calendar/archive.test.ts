import { describe, expect, it } from 'vitest'
import { archiveCutoff, archivedTimeLabel, isArchivedMonth, monthBounds, rangeProblem } from './archive'

describe('archiveCutoff', () => {
  it('今天往前推 2 年', () => {
    expect(archiveCutoff('2026-10-06')).toBe('2024-10-06')
  })

  it('2/29 推到沒有 2/29 的年份算 2/28（跟後端一樣）', () => {
    expect(archiveCutoff('2028-02-29')).toBe('2026-02-28')
  })
})

describe('isArchivedMonth', () => {
  const today = '2026-10-06'

  it('2 年以前的月份：有已經封存的日子', () => {
    expect(isArchivedMonth(2024, 9, today)).toBe(true)
    expect(isArchivedMonth(2022, 1, today)).toBe(true)
  })

  it('分界那個月：月初比分界早，前幾天已經封存了，也要說明', () => {
    expect(isArchivedMonth(2024, 10, today)).toBe(true)
  })

  it('反例：2 年內的月份不說明', () => {
    expect(isArchivedMonth(2024, 11, today)).toBe(false)
    expect(isArchivedMonth(2026, 10, today)).toBe(false)
  })

  it('反例：分界剛好是月初，那個月還沒有封存的日子', () => {
    expect(isArchivedMonth(2024, 10, '2026-10-01')).toBe(false)
    expect(isArchivedMonth(2024, 9, '2026-10-01')).toBe(true)
  })
})

describe('monthBounds', () => {
  it('整個月，兩端都含；二月照閏年算', () => {
    expect(monthBounds(2024, 3)).toEqual({ from: '2024-03-01', to: '2024-03-31' })
    expect(monthBounds(2024, 2)).toEqual({ from: '2024-02-01', to: '2024-02-29' })
    expect(monthBounds(2023, 2)).toEqual({ from: '2023-02-01', to: '2023-02-28' })
  })
})

describe('rangeProblem', () => {
  it('同一天、正常的期間都可以', () => {
    expect(rangeProblem('2024-03-05', '2024-03-05')).toBeNull()
    expect(rangeProblem('2024-03-01', '2024-03-31')).toBeNull()
  })

  it('反例：沒選日期、結束早於開始', () => {
    expect(rangeProblem('', '2024-03-05')).toBe('請選日期')
    expect(rangeProblem('2024-03-05', '')).toBe('請選日期')
    expect(rangeProblem('2024-03-05', '2024-03-01')).toBe('結束日期不能早於開始日期')
  })
})

describe('archivedTimeLabel', () => {
  it('同一天：日期帶年份，後面是起訖時間', () => {
    expect(archivedTimeLabel('2024-03-05T09:00:00', '2024-03-05T10:30:00')).toBe('2024年3月5日（週二） 09:00 – 10:30')
  })

  it('跨天：結束那天也寫出來；同一年不重複年份', () => {
    expect(archivedTimeLabel('2024-03-30T09:00:00', '2024-04-02T18:00:00')).toBe('2024年3月30日（週六） 09:00 – 4月2日（週二） 18:00')
  })

  it('跨年：結束那天也帶年份', () => {
    expect(archivedTimeLabel('2023-12-31T22:00:00', '2024-01-01T02:00:00')).toBe('2023年12月31日（週日） 22:00 – 2024年1月1日（週一） 02:00')
  })
})
