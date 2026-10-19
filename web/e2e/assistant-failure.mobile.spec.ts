// 失敗時的使用者體驗（#43）在手機版：助理在底部滑上來的面板裡，倒數和「再試一次」一樣要在。
// 每一種失敗的細節在 assistant-failure.desktop.spec.ts，這裡只確認手機版面也接上了。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test('手機：模型忙碌時，那句標「沒送出」，倒數完可以再試', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-06T11:30:00+08:00') })
  await fakeApi(page)
  let calls = 0
  await page.route('**/api/calendar/assistant', (route) =>
    calls++ === 0
      ? route.fulfill({ status: 503, json: { error: '後端的話', code: 'MODEL_BUSY', retryAfterSeconds: 5 }, headers: { 'Retry-After': '5' } })
      : route.fulfill({ status: 200, json: { conversationId: 'c1', reply: '明天沒有行程。', proposals: [] } }))
  await page.goto('/')

  await page.getByRole('button', { name: '開啟 AI 助理' }).click()
  const sheet = page.getByRole('dialog', { name: 'AI 助理' })
  const input = sheet.getByPlaceholder('例如：下週二早上10點面試')
  await input.fill('明天有什麼行程？')
  await input.press('Enter')

  await expect(sheet.getByText('沒送出')).toBeVisible()
  await expect(sheet.getByRole('button', { name: '5 秒後可以再試' })).toBeDisabled()
  await expect(sheet.getByRole('button', { name: '送出' })).toBeDisabled()
  await page.clock.runFor(5_000)
  await sheet.getByRole('button', { name: '再試一次' }).click()
  await expect(sheet.getByText('明天沒有行程。')).toBeVisible()
  await expect(sheet.getByText('沒送出')).toHaveCount(0)
})
