// 失敗時的使用者體驗（#43）：每一種失敗，畫面說了什麼、可以怎麼做。
//
// 助理的錯誤格式照 #44 的 AssistantFailure：{ error, code, retryAfterSeconds? }。nginx 自己回的（429、app 停掉時的 502/504）
// 沒有 code；網路斷了連回應都沒有。每一種用 page.route 演一次（後註冊的 route 先比對，蓋得過 fakeApi 的總攔截）。
//
// 倒數用 Playwright 的假時鐘（page.clock）：不必真的等 30 秒，也證明得了「倒數完之前送不出去」。
import { expect, test, type Page, type Route } from '@playwright/test'
import { fakeApi } from './fakeApi'

const INPUT = '例如：下週二早上10點面試'

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-06T11:30:00+08:00') })
})

type Reply = (route: Route) => Promise<void>

const ok: Reply = (route) => route.fulfill({ status: 200, json: { conversationId: 'c1', reply: '明天沒有行程。', proposals: [] } })
const failure = (status: number, code: string, retryAfterSeconds?: number): Reply => (route) =>
  route.fulfill({
    status,
    json: { error: '後端的話', code, ...(retryAfterSeconds ? { retryAfterSeconds } : {}) },
    headers: retryAfterSeconds ? { 'Retry-After': String(retryAfterSeconds) } : {},
  })

/** 助理照順序回這幾種；用完了就一直回最後一種。回傳收到幾次請求。 */
async function assistantReplies(page: Page, ...replies: Reply[]) {
  const calls = { count: 0 }
  await page.route('**/api/calendar/assistant', (route) => {
    const reply = replies[Math.min(calls.count, replies.length - 1)]
    calls.count++
    return reply(route)
  })
  return calls
}

async function ask(page: Page, text: string) {
  const input = page.getByPlaceholder(INPUT)
  await input.fill(text)
  await input.press('Enter')
}

const chat = (page: Page) => page.getByRole('complementary', { name: 'AI 助理' })

test('模型連不上（503 MODEL_UNAVAILABLE）：那句標「沒送出」、說行事曆照常可用，倒數 30 秒，倒數完之前送不出去', async ({ page }) => {
  await fakeApi(page)
  const calls = await assistantReplies(page, failure(503, 'MODEL_UNAVAILABLE', 30), ok)
  await page.goto('/')

  await ask(page, '明天下午三點和 Amy 開會')
  const panel = chat(page)
  await expect(panel.locator('.bubble.me', { hasText: '明天下午三點和 Amy 開會' })).toContainText('沒送出')
  const notice = panel.locator('[data-failure="unavailable"]')
  await expect(notice).toContainText('行事曆的其他功能照常可用')
  await expect(notice.getByRole('button', { name: '30 秒後可以再試' })).toBeDisabled()

  // 倒數中：可以先打字，但送出鈕、建議說法都按不了；打的字不會被清掉
  await panel.getByPlaceholder('30 秒後可以再問，可以先打字').fill('後天早上看牙醫')
  await expect(panel.getByRole('button', { name: '送出' })).toBeDisabled()
  await expect(panel.getByRole('button', { name: '明天有什麼行程？' })).toBeDisabled()
  await panel.getByPlaceholder(/秒後可以再問/).press('Enter')
  await page.clock.runFor(29_000)
  await expect(notice.getByRole('button', { name: '1 秒後可以再試' })).toBeDisabled()
  expect(calls.count).toBe(1)

  // 倒數完：可以再試；按「再試一次」送的是原本那句，正在打的字還在
  await page.clock.runFor(1_000)
  await notice.getByRole('button', { name: '再試一次' }).click()
  await expect(panel.getByText('明天沒有行程。')).toBeVisible()
  expect(calls.count).toBe(2)
  await expect(panel.locator('.bubble.me', { hasText: '明天下午三點和 Amy 開會' })).toHaveCount(1)
  await expect(panel.getByText('沒送出')).toHaveCount(0)
  await expect(panel.locator('[data-failure]')).toHaveCount(0)
  await expect(panel.getByLabel('輸入訊息')).toHaveValue('後天早上看牙醫')
})

test('忙碌（503 MODEL_BUSY）：倒數 5 秒', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, failure(503, 'MODEL_BUSY', 5))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="busy"]')
  await expect(notice).toContainText('問助理的人比較多')
  await expect(notice.getByRole('button', { name: '5 秒後可以再試' })).toBeDisabled()
  await page.clock.runFor(5_000)
  await expect(notice.getByRole('button', { name: '再試一次' })).toBeEnabled()
})

test('問太快（nginx 的 429，只有 Retry-After header、沒有 code）：照 header 倒數 60 秒', async ({ page }) => {
  await fakeApi(page)
  await page.route('**/api/calendar/assistant', (route) =>
    route.fulfill({ status: 429, json: { error: '問得太快了，請等一分鐘再試' }, headers: { 'Retry-After': '60' } }))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="rate-limited"]')
  await expect(notice).toContainText('問得太快了')
  await expect(notice.getByRole('button', { name: '60 秒後可以再試' })).toBeDisabled()
})

test('這次叫了模型、失敗（502 ASSISTANT_FAILED）：不用等，馬上可以再試', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, failure(502, 'ASSISTANT_FAILED'), ok)
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="failed"]')
  await expect(notice).toContainText('可以直接再試一次')
  await expect(chat(page).getByRole('button', { name: '送出' })).toBeDisabled() // 輸入框是空的
  await notice.getByRole('button', { name: '再試一次' }).click()
  await expect(chat(page).getByText('明天沒有行程。')).toBeVisible()
})

test('請求本身有問題（400）：沒有「再試一次」，那句放回輸入框讓使用者改', async ({ page }) => {
  await fakeApi(page)
  await page.route('**/api/calendar/assistant', (route) =>
    route.fulfill({ status: 400, json: { error: '對話太長了，請按「清空」開始新的對話', code: 'INVALID_REQUEST' } }))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="invalid"]')
  await expect(notice).toContainText('對話太長了')
  await expect(notice.getByRole('button', { name: /再試/ })).toHaveCount(0)
  await expect(chat(page).getByLabel('輸入訊息')).toHaveValue('明天有什麼行程？')
})

test('網路斷了（請求沒送出去）：說連不上、可以再試，不說行事曆照常可用', async ({ page }) => {
  await fakeApi(page)
  await page.route('**/api/calendar/assistant', (route) => route.abort('internetdisconnected'))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="offline"]')
  await expect(notice).toContainText('連不上伺服器')
  await expect(notice).not.toContainText('照常可用')
  await expect(notice.getByRole('button', { name: '再試一次' })).toBeEnabled()
})

test('app 停掉（nginx 自己回 504 的 HTML）：說伺服器沒回應、附狀態碼，不是一串看不懂的 HTTP 504', async ({ page }) => {
  await fakeApi(page)
  await page.route('**/api/calendar/assistant', (route) =>
    route.fulfill({ status: 504, contentType: 'text/html', body: '<html><body><h1>504 Gateway Time-out</h1></body></html>' }))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const notice = chat(page).locator('[data-failure="server-down"]')
  await expect(notice).toContainText('伺服器暫時沒有回應（HTTP 504）')
  await expect(notice).not.toContainText('照常可用')
})

test('等很久：10 秒之後「思考中」改說已經等了幾秒；10 秒之前不說', async ({ page }) => {
  await fakeApi(page)
  let answer: () => void = () => {}
  await page.route('**/api/calendar/assistant', (route) => new Promise<void>((resolve) => {
    answer = () => resolve(ok(route))
  }))
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  const typing = chat(page).locator('.bubble.typing')
  await page.clock.runFor(5_000)
  await expect(typing).toHaveText('思考中…')
  await page.clock.runFor(7_000)
  await expect(typing).toContainText('已等 12 秒')
  answer()
  await expect(chat(page).getByText('明天沒有行程。')).toBeVisible()
  await expect(typing).toHaveCount(0)
})

test('反例：正常回覆沒有任何失敗的樣子（沒送出、再試、警示）', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, ok)
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  await expect(chat(page).getByText('明天沒有行程。')).toBeVisible()
  await expect(chat(page).getByText('沒送出')).toHaveCount(0)
  await expect(chat(page).getByRole('button', { name: /再試/ })).toHaveCount(0)
  await expect(chat(page).locator('[data-failure]')).toHaveCount(0)
  await expect(chat(page).getByRole('button', { name: '明天有什麼行程？' })).toBeEnabled()
})

test('反例：只有最後一則說明有「再試一次」，舊的失敗不會被插回新對話中間', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, failure(502, 'ASSISTANT_FAILED'), ok)
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  await expect(chat(page).getByRole('button', { name: '再試一次' })).toBeVisible()
  await ask(page, '後天呢？')
  await expect(chat(page).getByText('明天沒有行程。')).toBeVisible()
  await expect(chat(page).getByRole('button', { name: '再試一次' })).toHaveCount(0)
})

test('登入過期（401）：還沒送出的那句放回草稿，重新登入後還在輸入框裡', async ({ page }) => {
  const api = await fakeApi(page)
  await page.route('**/api/calendar/assistant', (route) => route.fulfill({ status: 401, json: { error: '請先登入' } }))
  await page.goto('/')
  await expect(page.getByPlaceholder(INPUT)).toBeVisible()

  api.expireSession()
  await ask(page, '下週三早上九點看牙醫')
  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()
  await page.getByLabel('帳號名稱').fill('amy')
  await page.getByLabel('密碼').fill('correct-horse')
  await page.getByRole('button', { name: '登入', exact: true }).click()
  await expect(chat(page).getByLabel('輸入訊息')).toHaveValue('下週三早上九點看牙醫')
})

test('讀不到行程：有「重新讀取」，讀到了警示就消失', async ({ page }) => {
  await fakeApi(page)
  let down = true
  await page.route('**/api/calendar/events?**', (route) =>
    down ? route.fulfill({ status: 502, contentType: 'text/html', body: '<html>502 Bad Gateway</html>' }) : route.fallback())
  await page.goto('/')

  const alert = page.getByRole('main').getByRole('alert')
  await expect(alert).toContainText('讀不到行程：伺服器暫時沒有回應（HTTP 502）')
  down = false
  await alert.getByRole('button', { name: '重新讀取' }).click()
  await expect(alert).toHaveCount(0)
})

test('打開頁面時連不上伺服器：畫「連不上」，不是登入頁；連上了按「重新連線」就回到行事曆', async ({ page }) => {
  await fakeApi(page)
  let down = true
  await page.route('**/api/auth/me', (route) => (down ? route.abort('connectionrefused') : route.fallback()))
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '暫時連不上伺服器' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '登入' })).toHaveCount(0)
  down = false
  await page.getByRole('button', { name: '重新連線' }).click()
  await expect(chat(page)).toBeVisible()
})

test('反例：真的沒登入（401）還是登入頁，不是「連不上」', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '登入' })).toBeVisible()
  await expect(page.getByText('暫時連不上伺服器')).toHaveCount(0)
})

test('打開頁面時伺服器遲遲不回：1 秒後說「正在連線」，不是一片空白；1 秒內不說（平常不閃一下）', async ({ page }) => {
  await fakeApi(page)
  let answer: () => void = () => {}
  await page.route('**/api/auth/me', (route) => new Promise<void>((resolve) => {
    answer = () => resolve(route.fallback())
  }))
  await page.goto('/')

  await page.clock.runFor(500)
  await expect(page.getByText('正在連線…')).toHaveCount(0)
  await page.clock.runFor(600)
  await expect(page.getByRole('status')).toHaveText('正在連線…')
  answer()
  await expect(chat(page)).toBeVisible()
  await expect(page.getByText('正在連線…')).toHaveCount(0)
})

test('送出去、還沒回就重新整理：那句還在輸入框裡，不必重打', async ({ page }) => {
  await fakeApi(page)
  // 模型一直沒回：請求卡著，使用者等不及按了重新整理
  await page.route('**/api/calendar/assistant', () => new Promise<void>(() => {}))
  await page.goto('/')

  await ask(page, '下週三早上九點看牙醫')
  await expect(chat(page).locator('.bubble.typing')).toBeVisible()
  await page.reload()
  await expect(chat(page).getByLabel('輸入訊息')).toHaveValue('下週三早上九點看牙醫')
})

test('反例：回成功之後重新整理，輸入框是空的（送成功的那句不會一直留在草稿裡）', async ({ page }) => {
  await fakeApi(page)
  await assistantReplies(page, ok)
  await page.goto('/')

  await ask(page, '明天有什麼行程？')
  await expect(chat(page).getByText('明天沒有行程。')).toBeVisible()
  await page.reload()
  await expect(chat(page).getByLabel('輸入訊息')).toHaveValue('')
})
