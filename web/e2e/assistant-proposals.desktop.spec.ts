// AI 助理的提議卡片（#223）：卡片後來被採納還是拒絕，前端要把卡片的 proposalId 原樣帶回後端，後端核對過才算進採納率。
// 三條路各走一次：直接按「加入行事曆」、按「修改」改完再存、按「取消」。
// 助理本身是假的（fakeApi 每句話都回一張固定的卡片），這裡測的是前端有沒有把 id 帶對地方。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

async function askForCard(page: Page) {
  await page.getByPlaceholder('例如：下週二早上10點面試').fill('明天下午三點開會')
  await page.getByPlaceholder('例如：下週二早上10點面試').press('Enter')
  await expect(page.getByText('請確認卡片。')).toBeVisible()
}

test('按「加入行事曆」：存檔時帶著卡片的 proposalId', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await askForCard(page)

  await page.getByRole('button', { name: '加入行事曆', exact: true }).click()

  await expect(page.getByText('已加入行事曆')).toBeVisible()
  expect(api.saved).toEqual([[expect.objectContaining({ title: '開會', proposalId: 'card-1' })]])
  expect(api.dismissed).toEqual([])
})

test('按「修改」改完再存：表單存檔時還是帶著那張卡片的 proposalId（改過再存也算採納）', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await askForCard(page)

  await page.getByRole('button', { name: '修改' }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await form.getByLabel('標題').fill('開產品會')
  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(form).toBeHidden()
  expect(api.saved).toEqual([[expect.objectContaining({ title: '開產品會', proposalId: 'card-1' })]])
})

test('按「取消」：告訴後端按掉的是哪張卡片，沒有存檔', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await askForCard(page)

  await page.getByRole('button', { name: '取消', exact: true }).click()

  await expect(page.getByText('已取消')).toBeVisible()
  await expect.poll(() => api.dismissed).toEqual([['card-1']])
  expect(api.saved).toEqual([])
})
