// 手機版的「新增行程」有兩個（右下角的浮動按鈕、選到沒有行程的那天時清單裡的按鈕），儲存按鈕叫「儲存」。
// 只看原始碼的話會照桌面版寫、撞到 strict mode，改這些測試前先用瀏覽器打開手機版看。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

// 兩個「新增行程」名字完全一樣，exact 也分不開。用範圍限定：從「這天沒有行程」那一塊裡找，
// 不用 .first()、.last()：那是靠順序，版面一改就點到另一個，而且不會報錯
async function openFormFromEmptyDay(page: import('@playwright/test').Page) {
  await page.getByText('這天沒有行程').locator('..').getByRole('button', { name: '新增行程' }).click()
  // 整頁表單的標題；用 heading 確認真的換到表單那一頁了
  await expect(page.getByRole('heading', { name: '新增行程' })).toBeVisible()
}

test('手機版新增：從沒有行程的那天點進去，整頁表單填好按「儲存」', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await openFormFromEmptyDay(page)

  await page.getByLabel('標題').fill('晨跑')
  await page.getByRole('button', { name: '儲存', exact: true }).click()

  await expect(page.getByRole('heading', { name: '新增行程' })).toBeHidden()
  expect(api.saved).toEqual([[expect.objectContaining({ title: '晨跑', start: '2026-10-06T10:00' })]])
  // 從「新增行程」填的是手動，不帶提議卡片的 id，不會被算成採納助理的提議
  expect(api.saved[0][0]).not.toHaveProperty('proposalId')
})

test('手機版沒填標題：整頁表單裡顯示錯誤，沒有送出', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await openFormFromEmptyDay(page)

  await page.getByRole('button', { name: '儲存', exact: true }).click()

  await expect(page.getByRole('alert')).toHaveText('請輸入行程標題')
  expect(api.saved).toEqual([])
})
