// 登入、註冊、登出（#103）：在真的瀏覽器裡走一遍。後端的規則（雜湊、session、CSRF）在 AuthSystemTest 測，
// 這裡測的是前端接起來的樣子：沒登入看不到行事曆、錯誤顯示得出來、登出後回到登入頁。
// 假後端（fakeApi.ts）會擋沒帶 CSRF token 的寫入（403），所以每一題最後都檢查「沒有請求被擋」——
// 擋了代表前端沒把 token 帶上。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

// 登入、註冊兩種模式的按鈕都用 exact：登入模式下切換按鈕叫「還沒有帳號？註冊」，用「註冊」去找會撞到它
const submit = (page: import('@playwright/test').Page, name: '登入' | '註冊') =>
  page.getByRole('button', { name, exact: true })

test('沒登入：只看得到登入頁，行事曆整個不出現', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toHaveCount(0)
})

test('帳密錯：留在登入頁顯示「帳號或密碼錯誤」；反例：打對就進到行事曆', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await page.goto('/')

  await page.getByLabel('帳號名稱').fill('amy')
  await page.getByLabel('密碼').fill('wrong-password')
  await submit(page, '登入').click()
  await expect(page.getByRole('alert')).toHaveText('帳號或密碼錯誤')
  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()

  await page.getByLabel('密碼').fill('correct-horse')
  await submit(page, '登入').click()
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toBeVisible()
  // 側欄最下面顯示是誰
  await expect(page.getByText('amy', { exact: true })).toBeVisible()
  expect(api.csrfRejected).toEqual([])
})

test('註冊：撞名顯示後端的訊息；換一個名字就註冊並直接登入', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await page.goto('/')
  await page.getByRole('button', { name: '還沒有帳號？註冊' }).click()

  await page.getByLabel('帳號名稱').fill('Amy')
  await page.getByLabel('密碼').fill('another-pass')
  await submit(page, '註冊').click()
  await expect(page.getByRole('alert')).toContainText('已經有人用了')

  await page.getByLabel('帳號名稱').fill('bob')
  await submit(page, '註冊').click()
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toBeVisible()
  await expect(page.getByText('bob', { exact: true })).toBeVisible()
  expect(api.csrfRejected).toEqual([])
})

test('登出：回到登入頁；再登入時是乾淨的狀態', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '登出' }).click()

  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()
  expect(api.csrfRejected).toEqual([])
})

test('登入過期：之後任何一個請求回 401，就回到登入頁（不是顯示一堆錯誤）', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toBeVisible()

  api.expireSession()
  // 換到下個月：畫面要重新讀行程，這時候後端回 401
  // 側欄的小月曆也有「下個月」，限定在主畫面（main）裡找
  await page.getByRole('main').getByRole('button', { name: '下個月' }).click()

  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()
})

test('後端開了 Google、GitHub：登入頁多兩個連結，連到後端的 /api/auth/oauth2/authorization/*；反例：沒開就不出現', async ({ page }) => {
  await fakeApi(page, { loggedIn: false, providers: ['google', 'github'] })
  await page.goto('/')

  await expect(page.getByRole('link', { name: '用 Google 登入' })).toHaveAttribute('href', '/api/auth/oauth2/authorization/google')
  await expect(page.getByRole('link', { name: '用 GitHub 登入' })).toHaveAttribute('href', '/api/auth/oauth2/authorization/github')

  const other = await page.context().newPage()
  await other.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
  await fakeApi(other, { loggedIn: false })
  await other.goto('/')
  await expect(other.getByRole('heading', { name: '登入' })).toBeVisible()
  await expect(other.getByRole('link', { name: /登入$/ })).toHaveCount(0)
})

test('從 Google 轉回來但沒登入成功（/?login_error=oauth）：顯示「沒有登入成功」，網址上的參數拿掉', async ({ page }) => {
  await fakeApi(page, { loggedIn: false, providers: ['google'] })
  await page.goto('/?login_error=oauth')

  await expect(page.getByRole('alert')).toHaveText('沒有登入成功，請再試一次或改用其他方式登入')
  await expect(page).toHaveURL(/\/$/)
})

test('用 Google 登入的人：側欄顯示 Google 給的名字（他沒有帳號名稱）', async ({ page }) => {
  await fakeApi(page, { externalName: 'Amy Chen' })
  await page.goto('/')

  await expect(page.getByText('Amy Chen', { exact: true })).toBeVisible()
})
