// 新增表單的規則：欄位填得對不對、怎麼變成要送給後端的資料。
// 從 EventForm.vue 抽出來的純函式：不碰畫面、不碰 API，給一樣的欄位就得到一樣的結果，所以用單元測試就能把每種情況測透。
// EventForm 只負責畫面和接線（呼叫這裡、把錯誤顯示出來、存檔）。
//
// 存檔走同一個 POST /events，所以規則還是後端的 CalendarEvent 說了算；
// 這裡先擋最常見的幾種（沒標題、結束等於開始），讓使用者不必等一趟來回才知道。
import type { Category, EventFields } from '../calendar'
import { addDays } from '../monthGrid'

/** 表單上的欄位，都是使用者填的原樣（還沒去掉空白）。日期 'YYYY-MM-DD'，時間 'HH:mm'。 */
export interface FormFields {
  title: string
  date: string
  start: string
  end: string
  category: Category
  location: string
  note: string
}

/**
 * 結束時間早於開始時間：不當成錯誤，當成「結束在隔天」。
 * 不然「晚上十點到凌晨一點」在表單裡根本填不出來 —— 而 AI 解析得出來。
 * 'HH:mm' 字串可以直接比大小：位數固定，字典序就是時間順序。
 */
export function isOvernight(start: string, end: string): boolean {
  return start !== '' && end !== '' && end < start
}

/** 欄位 → 要送出的資料；有問題就回給使用者看的錯誤訊息。 */
export function toDraft(form: FormFields): { draft: EventFields } | { error: string } {
  if (!form.title.trim()) return { error: '請輸入行程標題' }
  if (!form.date) return { error: '請選擇日期' }
  if (!form.start || !form.end) return { error: '請選擇開始與結束時間' }
  if (form.end === form.start) return { error: '結束時間不能跟開始時間一樣' }
  return {
    draft: {
      title: form.title.trim(),
      start: `${form.date}T${form.start}`,
      end: `${isOvernight(form.start, form.end) ? addDays(form.date, 1) : form.date}T${form.end}`,
      category: form.category,
      // 只有空白的選填欄位當成沒填，送 null，不送一串空白
      location: form.location.trim() || null,
      note: form.note.trim() || null,
    },
  }
}
