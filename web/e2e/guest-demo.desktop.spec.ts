// 沒登入的人在登入頁按「以訪客身分試用」，看到原本的行事曆和助理對話框，只是功能鎖住：輸入框停用、只能點固定問題，
// 要存檔、新增時帶去登入。次數、IP、不呼叫模型由後端決定（GuestDemoSystemTest），這裡測畫面怎麼限制。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

const QUESTIONS = ['明天下午3點和 Amy 開會', '明天有什麼行程？', '這週哪天下午有空？']

async function enterAsGuest(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: '以訪客身分試用' }).click()
}

test('沒登入按「以訪客身分試用」：看到原本的行事曆和助理，輸入框停用；點固定問題顯示回答和明天的卡片，沒有呼叫模型、也沒有去讀行程', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)

  await expect(page.getByRole('heading', { name: '2026年10月' })).toBeVisible()
  await expect(page.getByPlaceholder('登入後就能直接打字問助理')).toBeDisabled()
  for (const question of QUESTIONS) await expect(page.getByRole('button', { name: question })).toBeVisible()

  await page.getByRole('button', { name: QUESTIONS[0] }).click()
  await expect(page.getByText('幫你整理成卡片了，確認後就會加進行事曆。')).toBeVisible()
  await expect(page.getByText('10月7日（週三）　15:00 – 16:00')).toBeVisible()
  await expect(page.getByText('今天還能試 2 次')).toBeVisible()

  expect(api.guestAsked).toBe(1)
  expect(api.assistantCalls).toBe(0)
  expect(api.unauthorized).toEqual([])
  expect(api.csrfRejected).toEqual([])
})

test('按卡片的「加入行事曆」：不存檔，換成登入頁並說明原因；「以訪客身分試用」回到行事曆', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)
  await page.getByRole('button', { name: QUESTIONS[0] }).click()

  await page.getByRole('button', { name: '加入行事曆', exact: true }).click()

  await expect(page.getByRole('heading', { name: '登入', level: 1 })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('登入後，助理的卡片就能直接加進行事曆')
  expect(api.saved).toEqual([])

  await page.getByRole('button', { name: '以訪客身分試用' }).click()
  await expect(page.getByRole('heading', { name: '2026年10月' })).toBeVisible()
})

test('按「新增行程」、側欄的「登入」：都換成登入頁', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)

  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('登入後就能新增行程')
  await page.getByRole('button', { name: '以訪客身分試用' }).click()

  await page.getByRole('button', { name: '登入', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登入', level: 1 })).toBeVisible()
  await expect(page.getByRole('status')).toHaveCount(0)
})

test('第 4 次被擋（429）：顯示後端的說明和註冊按鈕，固定問題停用；按了換成註冊；反例：前 3 次都看得到回答', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await enterAsGuest(page)

  for (const [i, question] of QUESTIONS.entries()) {
    await page.getByRole('button', { name: question }).click()
    await expect(page.getByText(`今天還能試 ${2 - i} 次`)).toBeVisible()
  }
  await page.getByRole('button', { name: QUESTIONS[1] }).click()

  await expect(page.getByText('今天的示範次數用完了，註冊後就能直接跟助理說話')).toBeVisible()
  await expect(page.getByRole('button', { name: QUESTIONS[0] })).toBeDisabled()

  await page.getByRole('button', { name: '註冊，直接跟助理說話' }).click()
  await expect(page.getByRole('heading', { name: '註冊', level: 1 })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('註冊之後就能直接跟助理說話，不限這幾題')
})

test('反例：登入後輸入框照常可以打字，固定問題照常交給模型', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')

  await expect(page.getByPlaceholder('例如：下週二早上10點面試')).toBeEnabled()
  await page.getByRole('button', { name: QUESTIONS[0] }).click()
  await expect(page.getByText('請確認卡片。')).toBeVisible()
  expect(api.assistantCalls).toBe(1)
  expect(api.guestAsked).toBe(0)
})
