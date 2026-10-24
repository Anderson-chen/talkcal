// 跟後端講話的共用部分：每個 API（行事曆、助理、帳號）都經過這裡的 send()。
//
// 認證（#103）帶來的兩件事集中在這裡，各 API 不必各自處理：
// - CSRF：會改東西的請求（POST、DELETE）要把 XSRF-TOKEN cookie 的值放進 X-XSRF-TOKEN header（Spring Security 的 csrf.spa()）。
//   別的網站騙瀏覽器送請求時 cookie 照樣會帶上，但它讀不到我們網域的 cookie，送不出對的 header —— 所以擋得住
// - 401：登入過期（或從沒登入）時，通知 App 換回登入畫面
//
// 登入狀態本身是 HttpOnly 的 session cookie，JavaScript 讀不到、也不用讀：瀏覽器自己會帶。

/** 後端回的 trace id 放在這個 header（後端的 TraceIdResponseFilter，#42）。 */
export const TRACE_ID_HEADER = 'X-Trace-Id'

/**
 * 後端回了錯誤。帶著那個請求的 trace id：畫面把它跟錯誤訊息一起顯示，使用者說「剛剛那次怪怪的」時，
 * 複製這串給我們，在 Grafana 的 Tempo 一貼就找到那一次（#42）。
 * 沒有就是 null：請求根本沒到 app（nginx 自己擋下的 429、app 掛掉時 nginx 回的 502）
 */
export class ApiError extends Error {
  constructor(message: string, readonly traceId: string | null) {
    super(message)
  }
}

/** 404：要的東西不存在。跟其他錯誤分開，呼叫端才能決定「重新讀一次」而不是只顯示錯誤。 */
export class NotFoundError extends ApiError {}

/** 401：沒登入、登入過期，或登入時帳密錯誤。 */
export class UnauthorizedError extends ApiError {}

/** 要顯示給使用者的錯誤：訊息，加上查得到那一次請求的 trace id（沒有就是 null）。 */
export interface Problem {
  text: string
  traceId: string | null
}

/** 接到的例外變成要顯示的錯誤。不是後端回的（連不上、前端自己的檢查）就沒有 trace id —— 也沒有 trace 可查。 */
export function problemOf(e: unknown): Problem {
  return { text: e instanceof Error ? e.message : String(e), traceId: e instanceof ApiError ? e.traceId : null }
}

/** 回應帶的 trace id。成功的回應也有：助理答得怪，也要查得到是哪一次。 */
export function traceIdOf(res: Response): string | null {
  return res.headers.get(TRACE_ID_HEADER)
}

/** 從 document.cookie 那種字串（"a=1; XSRF-TOKEN=abc"）讀出某個 cookie 的值，沒有就是 null。 */
export function readCookie(cookies: string, name: string): string | null {
  for (const part of cookies.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) return decodeURIComponent(value.join('='))
  }
  return null
}

/** 會改東西的方法要帶 CSRF token；GET 這類只讀的不用（也不該用 GET 改東西）。 */
export function needsCsrf(method: string | undefined): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes((method ?? 'GET').toUpperCase())
}

// 401 時要通知誰（App 註冊一個：換回登入畫面）
const unauthorizedListeners = new Set<() => void>()

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

interface Failure {
  error?: string
}

export interface SendOptions {
  // 預設送 JSON；登入要送表單（Spring Security 的 formLogin 吃的是表單）
  contentType?: string
  // 預設 401 會通知 App 回登入畫面。登入失敗也是 401，那時候不該跳走，要留在登入頁顯示錯誤
  notifyUnauthorized?: boolean
}

/**
 * 錯誤處理：非 2xx 一律丟 ApiError，訊息優先用後端回的 {"error": ...}，
 * 拿不到（proxy 連不上 app，回的不是 JSON）就退回 HTTP 狀態碼。trace id 從 header 拿，每一種錯誤都有，不必看 body。
 */
export async function send(url: string, init: RequestInit, options: SendOptions = {}): Promise<Response> {
  const headers: Record<string, string> = { 'Content-Type': options.contentType ?? 'application/json' }
  if (needsCsrf(init.method)) {
    const token = await csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const res = await fetch(url, { ...init, headers })
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as Failure | null
    const message = failure?.error ?? `HTTP ${res.status}`
    const traceId = traceIdOf(res)
    if (res.status === 401) {
      if (options.notifyUnauthorized !== false) unauthorizedListeners.forEach((listener) => listener())
      throw new UnauthorizedError(message, traceId)
    }
    throw res.status === 404 ? new NotFoundError(message, traceId) : new ApiError(message, traceId)
  }
  return res
}

/**
 * 手上的 CSRF token。登入、登出之後 Spring Security 會換一個新的（舊的作廢），
 * cookie 被清掉時就先 GET 一次，讓後端發新的 XSRF-TOKEN cookie 過來。
 */
async function csrfToken(): Promise<string | null> {
  let token = readCookie(document.cookie, 'XSRF-TOKEN')
  if (!token) {
    await fetch('/api/auth/me').catch(() => null)
    token = readCookie(document.cookie, 'XSRF-TOKEN')
  }
  return token
}
