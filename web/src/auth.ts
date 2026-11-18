// /api/auth/* 的契約，和「目前是誰」的狀態。
//
// session 在後端，瀏覽器只拿到 HttpOnly cookie，目前登入的人由 GET /api/auth/me 回傳。
// 登入、登出是 Spring Security 的端點：登入送表單格式，成功回 204。
// Google、GitHub 登入是整頁轉址，不經過這裡的 fetch；轉回首頁時 session 已經登入，App 照常 loadMe()。

import { ref } from 'vue'
import { message, onUnauthorized, send, UnauthorizedError } from './http'

/** id 是行程、對話的擁有者；name 給人看 */
export interface Me {
  id: string
  // 帳密登入的是帳號名稱，Google、GitHub 登入的是對方給的名字
  name: string
  // 只用 Google、GitHub 登入的帳號沒有帳號名稱
  username: string | null
  // 沒登入的訪客拿不到 Me，所以只有這兩種
  tier: 'free' | 'paid'
}

/** 帳密以外的登入方式，後端有設定 client id 的才會開 */
export type Provider = 'google' | 'github'

/** 查不到就當成沒有：只顯示帳密，不擋住登入頁 */
export async function loadProviders(): Promise<Provider[]> {
  try {
    const res = await send('/api/auth/providers', { method: 'GET' }, { notifyUnauthorized: false })
    const body = (await res.json()) as { providers: string[] }
    return body.providers.filter((p): p is Provider => p === 'google' || p === 'github')
  } catch {
    return []
  }
}

/** 整頁轉過去，後端再轉到 Google、GitHub */
export function providerLoginUrl(provider: Provider): string {
  return `/api/auth/oauth2/authorization/${provider}`
}

/**
 * - undefined：還不確定（剛打開頁面，還在等後端回應）
 * - null：沒登入
 * - Me：登入了
 */
export const me = ref<Me | null | undefined>(undefined)

// 任何 API 回 401（登入過期、在別的分頁登出）都換回登入畫面
onUnauthorized(() => {
  me.value = null
})

/**
 * 打開頁面時查不到「我是誰」的原因，null 是沒有問題。只有 401 才算沒登入：伺服器重啟時重新整理，
 * 畫登入頁會讓登入著的人以為被登出、去重打密碼，而那時登入也一樣會失敗
 */
export const meUnreachable = ref<string | null>(null)

/** 401 記成沒登入，其他錯誤丟出去 */
export async function loadMe(): Promise<void> {
  try {
    const res = await send('/api/auth/me', { method: 'GET' }, { notifyUnauthorized: false })
    me.value = (await res.json()) as Me
  } catch (e) {
    if (!(e instanceof UnauthorizedError)) throw e
    me.value = null
  }
}

/** 查不到就記下原因，畫「連不上」而不是登入頁 */
export async function connect(): Promise<void> {
  try {
    await loadMe()
    meUnreachable.value = null
  } catch (e) {
    meUnreachable.value = message(e)
  }
}

/** 帳密錯丟 UnauthorizedError 但不通知 App，留在登入頁顯示 */
export async function logIn(username: string, password: string): Promise<void> {
  await send('/api/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) },
    { contentType: 'application/x-www-form-urlencoded', notifyUnauthorized: false })
  await loadMe()
}

/** 後端的註冊不會自動登入，這裡接著登入，使用者感覺是一步 */
export async function signUp(username: string, password: string): Promise<void> {
  await send('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) },
    { notifyUnauthorized: false })
  await logIn(username, password)
}

export async function logOut(): Promise<void> {
  await send('/api/auth/logout', { method: 'POST' })
  me.value = null
}
