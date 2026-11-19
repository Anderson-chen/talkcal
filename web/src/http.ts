// 每個 API 都經過 send()，認證帶來的兩件事集中在這裡：
// - CSRF：會改東西的請求要把 XSRF-TOKEN cookie 的值放進 X-XSRF-TOKEN header（Spring Security 的 csrf.spa()）。
//   別的網站誘使瀏覽器送請求時 cookie 照樣會帶，但那個網站讀不到這個網域的 cookie，放不進 header
// - 401：通知 App 換回登入畫面
//
// 登入狀態是 HttpOnly 的 session cookie，JavaScript 讀不到，瀏覽器自己會帶。

/**
 * 後端或中間的 nginx 回了錯誤，或根本連不上。message 給人看，detail 給畫面決定顯示什麼、可以怎麼做：
 * - status：null 是沒拿到回應（網路斷了、伺服器整台連不上）
 * - code：後端分類好的原因，例如 MODEL_UNAVAILABLE；nginx 自己回的錯誤沒有
 * - retryAfterSeconds：body 的優先，沒有才看 Retry-After header（nginx 的 429 只有 header）
 */
export class ApiError extends Error {
  constructor(message: string, readonly detail: FailureDetail = NO_RESPONSE) {
    super(message)
  }
}

export interface FailureDetail {
  status: number | null
  code: string | null
  retryAfterSeconds: number | null
}

const NO_RESPONSE: FailureDetail = { status: null, code: null, retryAfterSeconds: null }

/** 跟其他錯誤分開，呼叫端才能選擇重新讀一次，不只是顯示錯誤 */
export class NotFoundError extends ApiError {}

/** 沒登入、登入過期，或登入時帳密錯誤 */
export class UnauthorizedError extends ApiError {}

export function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** fetch 本身丟例外時，瀏覽器的訊息是英文的「Failed to fetch」，看不出該怎麼辦 */
const UNREACHABLE_MESSAGE = '連不上伺服器，請確認網路連線後再試一次'

/** Retry-After 也可能是 HTTP 日期，這個後端和 nginx 都不用，解析不了就當成沒有 */
export function retryAfterOf(header: string | null): number | null {
  if (!header || !/^\d+$/.test(header.trim())) return null
  return Number(header.trim())
}

export function readCookie(cookies: string, name: string): string | null {
  for (const part of cookies.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) return decodeURIComponent(value.join('='))
  }
  return null
}

export function needsCsrf(method: string | undefined): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes((method ?? 'GET').toUpperCase())
}

const unauthorizedListeners = new Set<() => void>()
const quotaListeners = new Set<(message: string) => void>()

/** 每日次數用完（429、code = QUOTA_EXCEEDED）：App 跳出提示註冊的視窗。呼叫端照樣收到 ApiError，各自顯示錯誤 */
export function onQuotaExceeded(listener: (message: string) => void): () => void {
  quotaListeners.add(listener)
  return () => quotaListeners.delete(listener)
}

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

interface Failure {
  error?: string
  code?: string
  retryAfterSeconds?: number
}

export interface SendOptions {
  // 登入要送表單：Spring Security 的 formLogin 只收表單格式
  contentType?: string
  // 登入失敗也是 401，那時不該通知 App 換頁，要留在登入頁顯示錯誤
  notifyUnauthorized?: boolean
}

/**
 * 非 2xx 一律丟 ApiError，訊息優先用後端回的 {"error": ...}；proxy 連不上 app 時回的不是 JSON，就附上狀態碼。
 * 送不出去也丟 ApiError，呼叫端不必另外捕捉 fetch 丟出的 TypeError。
 */
export async function send(url: string, init: RequestInit, options: SendOptions = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('Content-Type', options.contentType ?? 'application/json')
  if (needsCsrf(init.method)) {
    const token = await csrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  let res: Response
  try {
    res = await fetch(url, { ...init, headers })
  } catch {
    throw new ApiError(UNREACHABLE_MESSAGE)
  }
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as Failure | null
    const message = failure?.error ?? (res.status >= 500 ? `伺服器暫時沒有回應（HTTP ${res.status}），請稍後再試` : `HTTP ${res.status}`)
    const detail: FailureDetail = {
      status: res.status,
      code: failure?.code ?? null,
      retryAfterSeconds: failure?.retryAfterSeconds ?? retryAfterOf(res.headers.get('Retry-After')),
    }
    if (res.status === 429 && detail.code === 'QUOTA_EXCEEDED') quotaListeners.forEach((listener) => listener(message))
    if (res.status === 401) {
      if (options.notifyUnauthorized !== false) unauthorizedListeners.forEach((listener) => listener())
      throw new UnauthorizedError(message, detail)
    }
    throw res.status === 404 ? new NotFoundError(message, detail) : new ApiError(message, detail)
  }
  return res
}

/**
 * 登入、登出之後 Spring Security 會換掉 token、清掉 cookie。沒有 cookie 時先 GET 一次，讓後端發新的過來。
 */
async function csrfToken(): Promise<string | null> {
  let token = readCookie(document.cookie, 'XSRF-TOKEN')
  if (!token) {
    await fetch('/api/auth/me').catch(() => null)
    token = readCookie(document.cookie, 'XSRF-TOKEN')
  }
  return token
}
