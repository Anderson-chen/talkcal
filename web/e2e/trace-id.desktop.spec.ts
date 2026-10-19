// 追蹤碼（#42）：使用者說「剛剛那次怪怪的」時，畫面上找得到那一次請求的 trace id，而且複製得走。
// 後端把它放在回應的 X-Trace-Id header（TraceIdResponseFilter），這裡驗前端有讀、有顯示在對的地方。
//
// 助理的 API 不在 fakeApi 裡（它只演行事曆和帳號），這裡各自用 page.route 演一種回應。
// 後註冊的 route 先比對，所以蓋得過 fakeApi 那個 /api/** 的總攔截。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi } from './fakeApi'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

/** 助理下一次回這個（status、body、要不要帶 X-Trace-Id）。 */
async function assistantReplies(page: Page, status: number, body: unknown, traceId: string | null) {
  await page.route('**/api/calendar/assistant', (route) =>
    route.fulfill({ status, json: body, headers: traceId ? { 'X-Trace-Id': traceId } : {} }))
}

async function ask(page: Page, text: string) {
  const input = page.getByPlaceholder('例如：下週二早上10點面試')
  await input.fill(text)
  await input.press('Enter')
}

test('助理的回覆底下有追蹤碼，按「複製」拿到整串；開場白沒有', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await fakeApi(page)
  await assistantReplies(page, 200, { conversationId: 'c1', reply: '明天沒有行程。', proposals: [] }, TRACE_ID)
  await page.goto('/')

  // 還沒問之前：開場白不是哪一次請求回的，不該有追蹤碼
  await expect(page.getByText('嗨！我是你的行事曆助理', { exact: false })).toBeVisible()
  await expect(page.getByText('追蹤碼')).toHaveCount(0)

  await ask(page, '明天有什麼行程？')
  const reply = page.locator('.bubble.ai', { hasText: '明天沒有行程。' })
  await expect(reply.getByText(TRACE_ID)).toBeVisible()

  await reply.getByRole('button', { name: `複製追蹤碼 ${TRACE_ID}` }).click()
  await expect(reply.getByRole('button', { name: `複製追蹤碼 ${TRACE_ID}` })).toHaveText('已複製')
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(TRACE_ID)
})

test('助理出錯：錯誤訊息跟追蹤碼一起出現', async ({ page }) => {
  await fakeApi(page)
  // 錯誤格式照 #44 的 AssistantFailure；畫面上的說法依 code 決定（#43），不照抄 error
  await assistantReplies(page, 502, { error: 'AI 助理失敗：Request failed', code: 'ASSISTANT_FAILED' }, TRACE_ID)
  await page.goto('/')

  await ask(page, '明天下午有空嗎？')
  const failure = page.locator('.bubble.ai', { hasText: '這次助理沒有回成功' })
  await expect(failure.getByText(TRACE_ID)).toBeVisible()
})

test('存檔失敗：表單的錯誤底下有追蹤碼', async ({ page }) => {
  await fakeApi(page)
  await page.route('**/api/calendar/events', (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 500, json: { error: '伺服器出了點問題' }, headers: { 'X-Trace-Id': TRACE_ID } })
      : route.fallback())
  await page.goto('/')

  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await form.getByLabel('標題').fill('產品週會')
  await form.getByRole('button', { name: '儲存行程' }).click()

  const alert = form.getByRole('alert')
  await expect(alert).toContainText('伺服器出了點問題')
  await expect(alert).toContainText(TRACE_ID)
})

test('反例：請求沒到 app 的錯誤（nginx 自己擋下的 429，沒有 X-Trace-Id）只顯示訊息，沒有追蹤碼', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, 429, { error: '問得太快了，請等一分鐘再試' }, null)
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  await expect(page.getByText('問得太快了，先休息一下再問', { exact: false })).toBeVisible()
  await expect(page.getByText('追蹤碼')).toHaveCount(0)
})

test('反例：表單自己的檢查沒送出請求，錯誤沒有追蹤碼', async ({ page }) => {
  await fakeApi(page)
  await page.goto('/')

  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(form.getByRole('alert')).toBeVisible()
  await expect(form.getByText('追蹤碼')).toHaveCount(0)
})
