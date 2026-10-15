// eventDraft.ts 的單元測試：新增表單的每一條規則。
// 從 EventForm 抽出來之後，不必掛元件、不必等畫面更新，一種情況一行。元件有沒有接好，交給 Playwright（e2e/）。
import { describe, expect, it } from 'vitest'
import { isOvernight, toDraft, type FormFields } from './eventDraft'

// 一份合法的表單；每個測試只改它要測的那一欄，一眼看得出差在哪
const valid: FormFields = {
  title: '產品週會', date: '2026-10-06', start: '09:00', end: '10:00', category: 'WORK', location: '', note: '',
}

describe('toDraft：擋下填錯的', () => {
  it.each([
    ['標題沒填', '請輸入行程標題', { title: '' }],
    ['標題只有空白', '請輸入行程標題', { title: '   ' }],
    ['日期沒填', '請選擇日期', { date: '' }],
    ['開始沒填', '請選擇開始與結束時間', { start: '' }],
    ['結束沒填', '請選擇開始與結束時間', { end: '' }],
    ['結束等於開始', '結束時間不能跟開始時間一樣', { start: '09:00', end: '09:00' }],
  ])('%s → %s', (_name, error, change) => {
    expect(toDraft({ ...valid, ...change })).toEqual({ error })
  })
})

describe('toDraft：整理成要送出的資料', () => {
  it('合法的表單：日期和時間合成 YYYY-MM-DDTHH:mm，空的選填欄位送 null', () => {
    expect(toDraft(valid)).toEqual({
      draft: { title: '產品週會', start: '2026-10-06T09:00', end: '2026-10-06T10:00', category: 'WORK', location: null, note: null },
    })
  })

  it('標題、地點、備註去掉頭尾空白；只有空白的選填欄位當成沒填', () => {
    const result = toDraft({ ...valid, title: '  週會  ', location: '  三樓  ', note: '   ' })
    expect(result).toMatchObject({ draft: { title: '週會', location: '三樓', note: null } })
  })

  it('結束早於開始（22:00 到 01:00）：結束在隔天', () => {
    expect(toDraft({ ...valid, start: '22:00', end: '01:00' })).toMatchObject({
      draft: { start: '2026-10-06T22:00', end: '2026-10-07T01:00' },
    })
  })

  it('跨月、跨年的隔天也算對', () => {
    expect(toDraft({ ...valid, date: '2026-12-31', start: '23:00', end: '00:30' })).toMatchObject({
      draft: { end: '2027-01-01T00:30' },
    })
  })
})

describe('isOvernight', () => {
  it('結束早於開始才算跨夜；有一邊沒填就不算（表單上不該冒出「（隔天）」）', () => {
    expect(isOvernight('22:00', '01:00')).toBe(true)
    expect(isOvernight('09:00', '10:00')).toBe(false)
    expect(isOvernight('09:00', '09:00')).toBe(false)
    expect(isOvernight('', '01:00')).toBe(false)
    expect(isOvernight('22:00', '')).toBe(false)
  })
})
