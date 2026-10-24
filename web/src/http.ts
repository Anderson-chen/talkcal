// 跟後端講話的共用部分：每個 API（行事曆、助理、帳號）都經過這裡的 send()。
//
// 認證（#103）帶來的兩件事集中在這裡，各 API 不必各自處理：
// - CSRF：會改東西的請求（POST、DELETE）要把 XSRF-TOKEN cookie 的值放進 X-XSRF-TOKEN header（Spring Security 的 csrf.spa()）。
//   別的網站騙瀏覽器送請求時 cookie 照樣會帶上，但它讀不到我們網域的 cookie，送不出對的 header —— 所以擋得住
// - 401：登入過期（或從沒登入）時，通知 App 換回登入畫面
//
// 登入狀態本身是 HttpOnly 的 session cookie，JavaScript 讀不到、也不用讀：瀏覽器自己會帶。

/** 404：要的東西不存在。跟其他錯誤分開，呼叫端才能決定「重新讀一次」而不是只顯示錯誤。 */
export class NotFoundError extends Error {}

/** 401：沒登入、登入過期，或登入時帳密錯誤。 */
export class UnauthorizedError extends Error {}

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
  // 這個 API 自己要多帶的 header（例如新增行程的 Idempotency-Key）
  headers?: Record<string, string>
}

/**
 * 錯誤處理：非 2xx 一律丟錯，訊息優先用後端回的 {"error": ...}，
 * 拿不到（proxy 連不上 app，回的不是 JSON）就退回 HTTP 狀態碼。
 */
export async function send(url: string, init: RequestInit, options: SendOptions = {}): Promise<Response> {
  const headers: Record<string, string> = { 'Content-Type': options.contentType ?? 'application/json', ...options.headers }
  if (needsCsrf(init.method)) {
    const token = await csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const res = await fetch(url, { ...init, headers })
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as Failure | null
    const message = failure?.error ?? `HTTP ${res.status}`
    if (res.status === 401) {
      if (options.notifyUnauthorized !== false) unauthorizedListeners.forEach((listener) => listener())
      throw new UnauthorizedError(message)
    }
    throw res.status === 404 ? new NotFoundError(message) : new Error(message)
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
