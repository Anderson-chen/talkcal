// 假的後端：用 page.route 把瀏覽器送往 /api/calendar/events 的請求攔下來，回假資料。
// 請求不會真的送出去，所以不需要後端、資料庫、模型。
//
// 它記得存過哪些行程：存檔之後畫面會重新查一次，新的那筆要查得到，月曆上才會出現。
// 測試可以：
//   - 預先放幾筆行程（events）
//   - 讓下一次存檔失敗（failNextSave）：模擬後端掛了
//   - 事後檢查前端送了什麼（saved）
import type { Page } from '@playwright/test'

export interface FakeEvent {
  id: string
  title: string
  start: string
  end: string
  category: 'WORK' | 'PERSONAL' | 'HEALTH' | 'SOCIAL'
  location: string | null
  note: string | null
}

export async function fakeApi(page: Page, events: FakeEvent[] = []) {
  const state = {
    events: [...events],
    // 每次 POST 收到的 body.events（前端送了什麼）
    saved: [] as Omit<FakeEvent, 'id'>[][],
    failNextSave: null as string | null,
  }

  await page.route('**/api/calendar/events**', async (route) => {
    const request = route.request()
    if (request.method() === 'GET') {
      // 照後端的規則：from 含、to 不含，跟期間有重疊的都算
      const url = new URL(request.url())
      const from = url.searchParams.get('from')!
      const to = url.searchParams.get('to')!
      const inRange = state.events.filter((e) => e.start.slice(0, 10) < to && e.end.slice(0, 10) >= from)
      return route.fulfill({ json: { events: inRange } })
    }
    if (request.method() === 'POST') {
      const body = request.postDataJSON() as { events: Omit<FakeEvent, 'id'>[] }
      state.saved.push(body.events)
      if (state.failNextSave) {
        const error = state.failNextSave
        state.failNextSave = null
        // 跟後端出錯時一樣的格式：{ error }，前端會把它顯示給使用者
        return route.fulfill({ status: 502, json: { error } })
      }
      const created = body.events.map((e, i) => ({ ...e, id: `e${state.events.length + i + 1}` }))
      state.events.push(...created)
      return route.fulfill({ status: 201, json: { events: created } })
    }
    return route.fallback()
  })

  return state
}
