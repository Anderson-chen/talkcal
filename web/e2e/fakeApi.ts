// 假後端：page.route 把 /api 的請求攔下來回假資料，不需要後端、資料庫、模型。
// 照後端的規則回應：存過的行程查得到、沒登入回 401、修改時 expected 對不上回 409、封存的行程只有 /archive/events 查得到。
// 會改東西的請求要帶跟 XSRF-TOKEN cookie 一樣的 X-XSRF-TOKEN header，不然 403：照單全收的話，測不出前端有沒有帶 token。
// AI 助理不呼叫模型，每句話都回一張固定的提議卡片。
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
  // 後端開了哪些 Google、GitHub 登入（預設沒有，跟沒設定 client id 的後端一樣）
  providers?: string[]
  // 已經用 Google、GitHub 登入的人：/me 回這個名字，沒有帳號名稱（跟後端一樣）
  externalName?: string
  // 登入的人的會員等級，預設免費（跟後端新註冊的帳號一樣）；顯示文字照後端的 MembershipTier
  tier?: 'free' | 'paid'
  // 已經封存的行程：不在 events 裡，月曆上看不到
  archived?: FakeEvent[]
  // 今天的訪客名額滿了：按「以訪客身分試用」回 429
  guestSlotsFull?: boolean
  // 訪客今天已經用掉的次數（後端每個 IP 每天：增刪改合計 10 次、問助理 3 次）
  guestUsed?: { writes?: number; assistant?: number }
}

// 訪客 session 的標記：跟後端一樣，訪客是一個沒有帳號名稱的帳號
const GUEST = '#guest'
const GUEST_ID = 'id-guest-1'
const GUEST_ME = { id: GUEST_ID, name: '訪客', username: null, tier: 'guest', tierLabel: '訪客' }
// 跟後端 V15 的 quota_bucket 一樣
const GUEST_LIMITS = { writes: 10, assistant: 3 }
const QUOTA_MESSAGES = {
  writes: '今天訪客新增、修改、刪除行程的次數用完了，註冊後就能繼續使用',
  assistant: '今天訪客問助理的次數用完了，註冊後就能繼續使用',
  signup: '今天的訪客名額已經滿了，請註冊帳號使用',
}

// 助理每句話都提議這一筆（明天下午三點開會），proposalId 依序是 card-1、card-2…
const PROPOSAL = { title: '開會', start: '2026-10-07T15:00:00', end: '2026-10-07T16:00:00', category: 'WORK', location: null, note: null }

const CSRF_TOKEN = 'fake-csrf-token'

// 跟後端 MembershipTier 的 label() 一樣
const TIER_LABELS = { free: '免費會員', paid: '付費會員' } as const

// 時間比的是牆上時間本身：前端表單送的是 "2026-10-06T10:00"，讀回來的是 "2026-10-06T10:00:00"，後端（LocalDateTime）當成同一個時間
const sameTime = (a: string, b: string) => a.padEnd(19, ':00').slice(0, 19) === b.padEnd(19, ':00').slice(0, 19)
const sameEvent = (a: Omit<FakeEvent, 'id'>, b: Omit<FakeEvent, 'id'>) =>
  a.title === b.title && sameTime(a.start, b.start) && sameTime(a.end, b.end) && a.category === b.category
  && (a.location ?? null) === (b.location ?? null) && (a.note ?? null) === (b.note ?? null)

export async function fakeApi(page: Page, options: Options = {}) {
  const state = {
    events: [...(options.events ?? [])],
    // 每次 POST /events 收到的 body.events（前端送了什麼）
    saved: [] as Omit<FakeEvent, 'id'>[][],
    // 每次 POST /events 帶的 Idempotency-Key，沒帶就是 null；跟 saved 一一對應
    saveKeys: [] as (string | null)[],
    failNextSave: null as string | null,
    // 註冊過的帳號：名稱 → 密碼
    accounts: new Map<string, string>([['amy', 'correct-horse']]),
    me: (options.loggedIn ?? true) ? 'amy' : null as string | null,
    // 訪客註冊時用的帳號名稱：跟後端一樣在同一個帳號上升級，之後用這個名稱登入的還是訪客原本的 ID
    upgradedGuest: null as string | null,
    guestUsed: { writes: options.guestUsed?.writes ?? 0, assistant: options.guestUsed?.assistant ?? 0 },
    // 被 403 擋下的請求（沒帶或帶錯 CSRF token）：測試可以檢查清單是空的
    csrfRejected: [] as string[],
    providers: [...(options.providers ?? [])],
    // 發過幾張助理卡片；每次 POST /assistant/dismissals 收到的 proposalIds
    cards: 0,
    dismissed: [] as string[][],
    // 每次 PUT、DELETE /events/{id} 收到什麼
    updated: [] as { id: string; expected: Omit<FakeEvent, 'id'>; event: Omit<FakeEvent, 'id'> }[],
    deleted: [] as string[],
    // 每次 GET /archive/events 的 from、to；下一次查封存要不要失敗
    archiveQueries: [] as { from: string; to: string }[],
    failNextArchive: false,
  }

  const tier = options.tier ?? 'free'
  const tierLabel = TIER_LABELS[tier]

  const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, json: body })
  // 跟後端 QuotaInterceptor 一樣的格式
  const quotaExceeded = (route: Route, error: string) => route.fulfill({
    status: 429, headers: { 'Retry-After': '3600' }, json: { error, code: 'QUOTA_EXCEEDED', retryAfterSeconds: 3600 },
  })

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
      if (state.me === GUEST) {
        return route.fulfill({ status: 200, headers: withCsrfCookie, json: GUEST_ME })
      }
      if (options.externalName && state.me) {
        return route.fulfill({ status: 200, headers: withCsrfCookie, json: { id: 'id-external', name: options.externalName, username: null, tier, tierLabel } })
      }
      return state.me
        ? route.fulfill({ status: 200, headers: withCsrfCookie, json: { id: state.me === state.upgradedGuest ? GUEST_ID : `id-${state.me}`, name: state.me, username: state.me, tier, tierLabel } })
        : route.fulfill({ status: 401, headers: withCsrfCookie, json: { error: '請先登入' } })
    }
    if (url.pathname === '/api/auth/guest' && method === 'POST') {
      if (options.guestSlotsFull) return quotaExceeded(route, QUOTA_MESSAGES.signup)
      state.me = GUEST
      return json(route, 201, GUEST_ME)
    }
    if (url.pathname === '/api/auth/providers') {
      // 跟後端一樣：沒設定 Google、GitHub 時是空陣列
      return json(route, 200, { providers: state.providers })
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
      if (state.me === GUEST) state.upgradedGuest = username
      return json(route, 201, { id: username === state.upgradedGuest ? GUEST_ID : `id-${username}`, name: username, username })
    }
    if (url.pathname === '/api/auth/logout') {
      state.me = null
      return route.fulfill({ status: 204 })
    }

    // ── 行事曆：要登入 ──
    if (!state.me) return json(route, 401, { error: '請先登入' })

    // ── 訪客的每日次數（後端在 controller 前面擋，這裡也在處理請求之前擋）──
    if (state.me === GUEST) {
      const writes = url.pathname.startsWith('/api/calendar/events') && ['POST', 'PUT', 'DELETE'].includes(method)
      const asks = url.pathname === '/api/calendar/assistant' && method === 'POST'
      if (writes && ++state.guestUsed.writes > GUEST_LIMITS.writes) return quotaExceeded(route, QUOTA_MESSAGES.writes)
      if (asks && ++state.guestUsed.assistant > GUEST_LIMITS.assistant) return quotaExceeded(route, QUOTA_MESSAGES.assistant)
    }

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
      state.saveKeys.push(request.headers()['idempotency-key'] ?? null)
      if (state.failNextSave) {
        const error = state.failNextSave
        state.failNextSave = null
        // 跟後端出錯時一樣的格式：{ error }，前端會把 error 顯示給使用者
        return json(route, 502, { error })
      }
      const created = body.events.map((e, i) => ({ ...e, id: `e${state.events.length + i + 1}` }))
      state.events.push(...created)
      return json(route, 201, { events: created })
    }
    // 修改：照後端的規則，expected 要跟現在那筆的每個欄位都一樣才改，不一樣 409、沒有那筆 404
    const one = url.pathname.match(/^\/api\/calendar\/events\/([^/]+)$/)
    if (one && method === 'PUT') {
      const body = request.postDataJSON() as { expected: Omit<FakeEvent, 'id'>; event: Omit<FakeEvent, 'id'> }
      state.updated.push({ id: one[1], ...body })
      const at = state.events.findIndex((e) => e.id === one[1])
      if (at < 0) return json(route, 404, { error: `找不到行程：${one[1]}` })
      const { id, ...current } = state.events[at]
      if (!sameEvent(current, body.expected)) return json(route, 409, { error: '這筆行程剛被改過了，請重新整理後再改' })
      state.events[at] = { ...body.event, id }
      return json(route, 200, state.events[at])
    }
    if (one && method === 'DELETE') {
      state.deleted.push(one[1])
      const at = state.events.findIndex((e) => e.id === one[1])
      if (at < 0) return json(route, 404, { error: `找不到行程：${one[1]}` })
      state.events.splice(at, 1)
      return route.fulfill({ status: 204 })
    }
    // 封存：照後端的規則，from、to 都含，跟範圍有重疊的都算（剛好在 from 00:00 結束的不算）
    if (url.pathname === '/api/calendar/archive/events' && method === 'GET') {
      const from = url.searchParams.get('from')!
      const to = url.searchParams.get('to')!
      state.archiveQueries.push({ from, to })
      if (state.failNextArchive) {
        state.failNextArchive = false
        // 跟後端物件儲存連不上時一樣：502，訊息不帶內部位址
        return json(route, 502, { error: '封存的資料暫時讀不到，請稍後再試' })
      }
      if (from > to) return json(route, 400, { error: `開始日期（${from}）不可晚於結束日期（${to}）` })
      // 開始那天不晚於 to = 開始早於 to 隔天 00:00；'YYYY-MM-DD...' 字串直接比大小就是時間順序
      const overlapping = (options.archived ?? [])
        .filter((e) => e.start.slice(0, 10) <= to && e.end.padEnd(19, ':00') > `${from}T00:00:00`)
        .sort((a, b) => a.start.localeCompare(b.start))
      return json(route, 200, { events: overlapping })
    }
    if (url.pathname === '/api/calendar/assistant' && method === 'POST') {
      state.cards++
      return json(route, 200, {
        conversationId: '00000000-0000-4000-8000-000000000036',
        reply: '請確認卡片。',
        proposals: [{ ...PROPOSAL, proposalId: `card-${state.cards}` }],
      })
    }
    if (url.pathname === '/api/calendar/assistant/dismissals' && method === 'POST') {
      state.dismissed.push((request.postDataJSON() as { proposalIds: string[] }).proposalIds)
      return route.fulfill({ status: 204 })
    }
    return route.fallback()
  })

  return {
    ...state,
    get saved() { return state.saved },
    get saveKeys() { return state.saveKeys },
    get csrfRejected() { return state.csrfRejected },
    get dismissed() { return state.dismissed },
    get updated() { return state.updated },
    get deleted() { return state.deleted },
    get events() { return state.events },
    get archiveQueries() { return state.archiveQueries },
    set failNextSave(error: string | null) { state.failNextSave = error },
    set failNextArchive(fail: boolean) { state.failNextArchive = fail },
    /** 模擬 session 過期：之後的請求都當成沒登入 */
    expireSession() { state.me = null },
  }
}
