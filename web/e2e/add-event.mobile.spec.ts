// 手機版的新增行程（Pixel 7，412px 寬）。跟桌面版是同一個 EventForm，但版面完全不同：
//   - 入口：沒有左側欄，「新增行程」有兩個 —— 右下角的浮動按鈕、選到沒有行程的那天時清單裡的按鈕
//   - 表單：不是對話框，是整頁（上面一條：取消、新增行程、儲存）
//   - 儲存按鈕叫「儲存」，不是桌面版的「儲存行程」
// 這些是用瀏覽器實際打開手機版看出來的，只看原始碼會照桌面版寫、撞到 strict mode。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

// 兩個「新增行程」名字完全一樣，exact 也分不開。用範圍限定：從「這天沒有行程」那一塊裡找，
// 不用 .first()、.last() —— 那是靠順序，版面一改就點到另一個，而且不會報錯
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
  // 從「新增行程」填的是手動，不能算成採納助理的提議（#36 的採納率）
  expect(api.origins).toEqual(['MANUAL'])
})

test('手機版沒填標題：整頁表單裡顯示錯誤，沒有送出', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await openFormFromEmptyDay(page)

  await page.getByRole('button', { name: '儲存', exact: true }).click()

  await expect(page.getByRole('alert')).toHaveText('請輸入行程標題')
  expect(api.saved).toEqual([])
})
