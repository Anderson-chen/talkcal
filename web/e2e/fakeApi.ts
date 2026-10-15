// 假的後端：用 page.route 把瀏覽器送往 /api 的請求攔下來，回假資料。
// 請求不會真的送出去，所以不需要後端、資料庫、模型。
//
// 它記得存過哪些行程：存檔之後畫面會重新查一次，新的那筆要查得到，月曆上才會出現。
// 帳號（#103）也照後端的規則演：
//   - 沒登入時行事曆的 API 回 401
//   - 會改東西的請求（POST、DELETE）要帶 X-XSRF-TOKEN header，而且要跟發出去的 XSRF-TOKEN cookie 一樣，不然 403
//     —— 這樣才測得出前端真的有帶 token，而不是假後端照單全收
// 測試可以：
//   - 預先放幾筆行程（events）、決定一開始有沒有登入（loggedIn）
//   - 讓下一次存檔失敗（failNextSave）：模擬後端掛了
//   - 讓登入在下一個請求失效（expireSession）：模擬 session 過期
//   - 事後檢查前端送了什麼（saved）
import type { Page, Route } from '@playwright/test'

export interface FakeEvent {
  id: string
  title: string
  start: string
  end: string
  category: 'WORK' | 'PERSONAL' | 'HEALTH' | 'SOCIAL'
  location: string | null
  note: string | null
}

interface Options {
  events?: FakeEvent[]
  // 預設已經登入成 amy：測行事曆的那些測試不必每個都先登入
  loggedIn?: boolean
}

const CSRF_TOKEN = 'fake-csrf-token'

export async function fakeApi(page: Page, options: Options = {}) {
  const state = {
    events: [...(options.events ?? [])],
    // 每次 POST /events 收到的 body.events（前端送了什麼）
    saved: [] as Omit<FakeEvent, 'id'>[][],
    failNextSave: null as string | null,
    // 註冊過的帳號：名稱 → 密碼
    accounts: new Map<string, string>([['amy', 'correct-horse']]),
    me: (options.loggedIn ?? true) ? 'amy' : null as string | null,
    // 被 403 擋下的請求（沒帶或帶錯 CSRF token）：測試可以檢查它是空的
    csrfRejected: [] as string[],
  }

  const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, json: body })

  // 跟 Spring Security 一樣，回應時把 XSRF-TOKEN cookie 發給瀏覽器（JavaScript 讀得到）
  const withCsrfCookie = { 'Set-Cookie': `XSRF-TOKEN=${CSRF_TOKEN}; Path=/` }

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method !== 'GET' && (await request.headerValue('x-xsrf-token')) !== CSRF_TOKEN) {
      state.csrfRejected.push(`${method} ${url.pathname}`)
      return json(route, 403, { error: '請重新整理頁面再試一次' })
    }

    // ── 帳號 ──
    if (url.pathname === '/api/auth/me') {
      return state.me
        ? route.fulfill({ status: 200, headers: withCsrfCookie, json: { id: `id-${state.me}`, username: state.me } })
        : route.fulfill({ status: 401, headers: withCsrfCookie, json: { error: '請先登入' } })
    }
    if (url.pathname === '/api/auth/login') {
      // 登入是表單格式（跟 Spring Security 的 formLogin 一樣），不是 JSON
      const form = new URLSearchParams(request.postData() ?? '')
      const username = (form.get('username') ?? '').trim().toLowerCase()
      if (state.accounts.get(username) !== form.get('password')) return json(route, 401, { error: '帳號或密碼錯誤' })
      state.me = username
      return route.fulfill({ status: 204 })
    }
    if (url.pathname === '/api/auth/register') {
      const body = request.postDataJSON() as { username: string; password: string }
      const username = body.username.trim().toLowerCase()
      if (state.accounts.has(username)) return json(route, 409, { error: `帳號名稱「${username}」已經有人用了，換一個試試` })
      state.accounts.set(username, body.password)
      return json(route, 201, { id: `id-${username}`, username })
    }
    if (url.pathname === '/api/auth/logout') {
      state.me = null
      return route.fulfill({ status: 204 })
    }

    // ── 行事曆：要登入 ──
    if (!state.me) return json(route, 401, { error: '請先登入' })

    if (url.pathname === '/api/calendar/events' && method === 'GET') {
      // 照後端的規則：from 含、to 不含，跟期間有重疊的都算
      const from = url.searchParams.get('from')!
      const to = url.searchParams.get('to')!
      const inRange = state.events.filter((e) => e.start.slice(0, 10) < to && e.end.slice(0, 10) >= from)
      return json(route, 200, { events: inRange })
    }
    if (url.pathname === '/api/calendar/events' && method === 'POST') {
      const body = request.postDataJSON() as { events: Omit<FakeEvent, 'id'>[] }
      state.saved.push(body.events)
      if (state.failNextSave) {
        const error = state.failNextSave
        state.failNextSave = null
        // 跟後端出錯時一樣的格式：{ error }，前端會把它顯示給使用者
        return json(route, 502, { error })
      }
      const created = body.events.map((e, i) => ({ ...e, id: `e${state.events.length + i + 1}` }))
      state.events.push(...created)
      return json(route, 201, { events: created })
    }
    return route.fallback()
  })

  return {
    ...state,
    get saved() { return state.saved },
    get csrfRejected() { return state.csrfRejected },
    set failNextSave(error: string | null) { state.failNextSave = error },
    /** 模擬 session 過期：之後的請求都當成沒登入 */
    expireSession() { state.me = null },
  }
}
