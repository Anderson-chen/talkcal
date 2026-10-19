// 跟後端 /api/auth/* 的契約，以及「目前是誰」的狀態（#103）。
//
// 登入狀態存在後端的 session，瀏覽器只拿到一個 HttpOnly cookie，這裡讀不到也不用讀；
// 前端要知道「有沒有登入、是誰」，就問 GET /api/auth/me。
//
// 登入、登出走 Spring Security 的 /api/auth/login、/api/auth/logout：登入要送表單格式（不是 JSON），成功回 204。
//
// 用 Google、GitHub 登入（#105）不經過這裡的 fetch：那是整頁轉址（到 Google 同意、再轉回首頁），
// 回到首頁時 session 已經是登入的，App 照常 loadMe() 就知道是誰。

import { ref } from 'vue'
import { onUnauthorized, problemOf, send, UnauthorizedError, type Problem } from './http'

/** 目前登入的人。id 是行程、對話的擁有者；name 是給人看的。 */
export interface Me {
  id: string
  // 帳密登入的是帳號名稱，Google、GitHub 登入的是對方給的名字
  name: string
  // 只用 Google、GitHub 登入的帳號沒有帳號名稱
  username: string | null
}

/** 除了帳密之外開了的登入方式（後端有設定 client id 的才有）。 */
export type Provider = 'google' | 'github'

/** 開了哪些登入方式。問不到（後端沒開、網路斷了）就當成沒有：只顯示帳密，不擋住登入頁。 */
export async function loadProviders(): Promise<Provider[]> {
  try {
    const res = await send('/api/auth/providers', { method: 'GET' }, { notifyUnauthorized: false })
    const body = (await res.json()) as { providers: string[] }
    return body.providers.filter((p): p is Provider => p === 'google' || p === 'github')
  } catch {
    return []
  }
}

/** 「用 Google 登入」按鈕連到哪裡：整頁轉過去，後端再轉到 Google。 */
export function providerLoginUrl(provider: Provider): string {
  return `/api/auth/oauth2/authorization/${provider}`
}

/**
 * 整個 App 共用的登入狀態：
 * - undefined：還不知道（剛打開頁面，正在問後端）
 * - null：沒登入
 * - Me：登入了
 */
export const me = ref<Me | null | undefined>(undefined)

// 任何 API 回 401（登入過期、在別的分頁登出了）：換回登入畫面
onUnauthorized(() => {
  me.value = null
})

/**
 * 打開頁面時問不到「我是誰」的原因（#43）。null 是沒出事。
 * 只有 401 才代表「沒登入」；連不上、伺服器出錯時就說連不上，不畫登入頁 —— 登入著的人在伺服器重啟時重新整理，
 * 看到登入頁會以為被登出、去重打密碼，而那時候登入也一樣會失敗
 */
export const meUnreachable = ref<Problem | null>(null)

/** 打開頁面時問一次：有登入就記下是誰，沒有就是 null；問不到（不是 401）就留在「還不知道」，記下原因。 */
export async function loadMe(): Promise<void> {
  try {
    const res = await send('/api/auth/me', { method: 'GET' }, { notifyUnauthorized: false })
    me.value = (await res.json()) as Me
    meUnreachable.value = null
  } catch (e) {
    if (e instanceof UnauthorizedError) {
      me.value = null
      meUnreachable.value = null
    } else if (me.value === undefined) {
      meUnreachable.value = problemOf(e)
    } else {
      // 已經在登入頁（me 是 null），登入成功後卻問不到是誰：照舊丟出去，由登入頁顯示錯誤
      throw e
    }
  }
}

/** 登入。帳密錯會丟錯（訊息是「帳號或密碼錯誤」），留在登入頁顯示，不跳走。 */
export async function logIn(username: string, password: string): Promise<void> {
  await send('/api/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) },
    { contentType: 'application/x-www-form-urlencoded', notifyUnauthorized: false })
  await loadMe()
}

/** 註冊再登入：後端的註冊不會自動登入，這裡接著登入，使用者感覺上是一步。 */
export async function signUp(username: string, password: string): Promise<void> {
  await send('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) },
    { notifyUnauthorized: false })
  await logIn(username, password)
}

export async function logOut(): Promise<void> {
  await send('/api/auth/logout', { method: 'POST' })
  me.value = null
}
