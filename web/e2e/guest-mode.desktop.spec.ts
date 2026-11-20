// 訪客模式：登入頁按「以訪客身分試用」，進到跟會員一樣的行事曆和助理，只是每天有次數上限。
// 次數怎麼算、IP、Redis、訪客併進正式帳號由後端決定（GuestModeSystemTest），這裡測畫面怎麼進出、用完時怎麼提示。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

const INPUT = '例如：下週二早上10點面試'

async function enterAsGuest(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: '以訪客身分試用' }).click()
  await expect(page.getByRole('button', { name: '新增行程', exact: true })).toBeVisible()
}

async function ask(page: Page, text: string) {
  await page.getByPlaceholder(INPUT).fill(text)
  await page.getByPlaceholder(INPUT).press('Enter')
}

const quotaDialog = (page: Page) => page.getByRole('dialog', { name: '今天的次數用完了' })

test('按「以訪客身分試用」：進到行事曆，側欄是「訪客」和註冊、登入；可以自由打字問助理', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)

  await expect(page.getByText('訪客', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '註冊', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '登出' })).toHaveCount(0)

  await ask(page, '明天下午三點開會')
  await expect(page.getByText('請確認卡片。')).toBeVisible()
  expect(api.csrfRejected).toEqual([])
})

test('今天的訪客名額滿了：留在登入頁，顯示後端的說明', async ({ page }) => {
  await fakeApi(page, { loggedIn: false, guestSlotsFull: true })
  await page.goto('/')

  await page.getByRole('button', { name: '以訪客身分試用' }).click()

  await expect(page.getByRole('alert')).toHaveText('今天的訪客名額已經滿了，請註冊帳號使用')
  await expect(page.getByRole('heading', { name: '登入', level: 1 })).toBeVisible()
  await expect(quotaDialog(page)).toHaveCount(0)
})

test('第 4 次問助理：跳出視窗提示註冊；按「註冊」到註冊頁，「先不登入」回來時剛才的對話還在', async ({ page }) => {
  await fakeApi(page, { loggedIn: false, guestUsed: { assistant: 2 } })
  await enterAsGuest(page)

  await ask(page, '明天下午三點開會')
  await expect(page.getByText('請確認卡片。')).toBeVisible()
  await ask(page, '後天呢？')

  await expect(quotaDialog(page)).toContainText('今天訪客問助理的次數用完了，註冊後就能繼續使用')
  await quotaDialog(page).getByRole('button', { name: '註冊' }).click()
  await expect(page.getByRole('heading', { name: '註冊', level: 1 })).toBeVisible()
  await expect(page.getByRole('status')).toContainText('註冊之後次數就不受訪客的限制')

  await page.getByRole('button', { name: '先不登入，回到行事曆' }).click()
  await expect(page.getByText('請確認卡片。')).toBeVisible()
})

test('增刪改的次數用完：存行程時跳出視窗，行程沒有存進去', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false, guestUsed: { writes: 10 } })
  await enterAsGuest(page)

  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await form.getByLabel('標題').fill('牙醫')
  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(quotaDialog(page)).toContainText('今天訪客新增、修改、刪除行程的次數用完了')
  expect(api.events).toEqual([])
})

test('增刪改的次數用完：助理照常提議卡片，按「加入行事曆」也跳出視窗，行程沒有存進去', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false, guestUsed: { writes: 10 } })
  await enterAsGuest(page)

  await ask(page, '明天下午三點開會')
  await expect(page.getByText('請確認卡片。')).toBeVisible()
  await page.getByRole('button', { name: '加入行事曆', exact: true }).click()

  await expect(quotaDialog(page)).toContainText('今天訪客新增、修改、刪除行程的次數用完了')
  await expect(page.getByText('已加入行事曆')).toHaveCount(0)
  expect(api.saved).toEqual([])
  expect(api.events).toEqual([])
})

test('訪客登入已有的帳號：換成那個帳號之後，接得回訪客時的對話', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  // 後端登入時已經把訪客的對話搬到 amy 名下，amy 讀得到同一段
  await page.route('**/api/calendar/assistant/00000000-0000-4000-8000-000000000036', (route) => route.fulfill({
    status: 200,
    json: { conversationId: '00000000-0000-4000-8000-000000000036', messages: [
      { role: 'user', text: '明天下午三點開會' }, { role: 'assistant', text: '請確認卡片。' },
    ] },
  }))
  await enterAsGuest(page)
  await ask(page, '明天下午三點開會')
  await expect(page.getByText('請確認卡片。')).toBeVisible()

  await page.getByRole('button', { name: '登入', exact: true }).click()
  await page.getByLabel('帳號名稱').fill('amy')
  await page.getByLabel('密碼').fill('correct-horse')
  await page.locator('form').getByRole('button', { name: '登入', exact: true }).click()

  await expect(page.getByRole('button', { name: '登出' })).toBeVisible()
  await expect(page.getByText('明天下午三點開會', { exact: true })).toBeVisible()
  await expect(page.getByText('請確認卡片。')).toBeVisible()
})

test('訪客註冊：變成免費會員（側欄有「登出」），帳號 ID 不變', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)

  await page.getByRole('button', { name: '註冊', exact: true }).click()
  await page.getByLabel('帳號名稱').fill('bob')
  await page.getByLabel('密碼').fill('another-pass')
  await page.locator('form').getByRole('button', { name: '註冊', exact: true }).click()

  await expect(page.getByRole('button', { name: '登出' })).toBeVisible()
  await expect(page.getByText('bob', { exact: true })).toBeVisible()
  const me = await page.evaluate(async () => (await fetch('/api/auth/me')).json())
  expect(me).toMatchObject({ id: 'id-guest-1', tier: 'free' })
})
