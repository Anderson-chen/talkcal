// 跟後端 /api/auth/* 的契約，以及「目前是誰」的狀態（#103）。
//
// 登入狀態存在後端的 session，瀏覽器只拿到一個 HttpOnly cookie，這裡讀不到也不用讀；
// 前端要知道「有沒有登入、是誰」，就問 GET /api/auth/me。
//
// 登入、登出走 Spring Security 的 /api/auth/login、/api/auth/logout：登入要送表單格式（不是 JSON），成功回 204。

import { ref } from 'vue'
import { onUnauthorized, send } from './http'

/** 目前登入的人。id 是行程、對話的擁有者；username 是給人看的。 */
export interface Me {
  id: string
  username: string
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

/** 打開頁面時問一次：有登入就記下是誰，沒有就是 null。 */
export async function loadMe(): Promise<void> {
  try {
    const res = await send('/api/auth/me', { method: 'GET' }, { notifyUnauthorized: false })
    me.value = (await res.json()) as Me
  } catch {
    me.value = null
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
