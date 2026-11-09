// http.ts 的純函式：讀 cookie、哪些請求要帶 CSRF token。
// send() 本身（真的帶上 header、401 回登入頁）在 Playwright 裡測：e2e/fakeApi.ts 會檢查 header，沒帶就回 403。
import { describe, expect, it } from 'vitest'
import { needsCsrf, readCookie, retryAfterOf } from './http'

describe('readCookie', () => {
  it('從 document.cookie 那種字串裡找出指定的那一個', () => {
    expect(readCookie('theme=sky; XSRF-TOKEN=abc123; other=1', 'XSRF-TOKEN')).toBe('abc123')
  })

  it('沒有就是 null；名字只是前綴相同的不算（XSRF-TOKEN-OLD 不是 XSRF-TOKEN）', () => {
    expect(readCookie('theme=sky', 'XSRF-TOKEN')).toBeNull()
    expect(readCookie('XSRF-TOKEN-OLD=stale', 'XSRF-TOKEN')).toBeNull()
    expect(readCookie('', 'XSRF-TOKEN')).toBeNull()
  })

  it('值裡有 = 也完整拿回來，編碼過的會解開', () => {
    expect(readCookie('XSRF-TOKEN=a=b=c', 'XSRF-TOKEN')).toBe('a=b=c')
    expect(readCookie('XSRF-TOKEN=a%2Fb', 'XSRF-TOKEN')).toBe('a/b')
  })
})

describe('needsCsrf', () => {
  it('會改東西的方法要帶：POST、PUT、PATCH、DELETE（大小寫不分）', () => {
    for (const method of ['POST', 'put', 'PATCH', 'delete']) expect(needsCsrf(method)).toBe(true)
  })

  it('反例：只讀的不用帶；沒寫 method 就是 GET', () => {
    for (const method of ['GET', 'head', 'OPTIONS', undefined]) expect(needsCsrf(method)).toBe(false)
  })
})

describe('retryAfterOf', () => {
  it('Retry-After 的秒數', () => {
    expect(retryAfterOf('30')).toBe(30)
    expect(retryAfterOf(' 5 ')).toBe(5)
  })

  it('反例：沒有、或是 HTTP 日期格式（我們不用）就當成沒有，不倒數成奇怪的數字', () => {
    expect(retryAfterOf(null)).toBeNull()
    expect(retryAfterOf('Wed, 21 Oct 2026 07:28:00 GMT')).toBeNull()
    expect(retryAfterOf('-1')).toBeNull()
  })
})
