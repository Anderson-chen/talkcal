// 沒登入的訪客在登入頁旁邊點固定問題，看助理預先寫好的回答。
// 次數、IP、不呼叫模型由後端決定（GuestDemoSystemTest），這裡測前端怎麼顯示、按了卡片和看滿之後帶去哪裡。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

const demo = (page: import('@playwright/test').Page) => page.getByRole('region', { name: '先看看助理怎麼回答' })

test('沒登入看得到 3 個固定問題，點了顯示預先寫好的回答和明天的卡片；沒有輸入框，也沒有呼叫助理的 API', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await page.goto('/')

  const box = demo(page)
  for (const question of ['明天下午3點和 Amy 開會', '明天有什麼行程？', '這週哪天下午有空？']) {
    await expect(box.getByRole('button', { name: question })).toBeVisible()
  }
  await expect(box.getByRole('textbox')).toHaveCount(0)

  await box.getByRole('button', { name: '明天下午3點和 Amy 開會' }).click()
  await expect(box.getByText('幫你整理成卡片了，確認後就會加進行事曆。')).toBeVisible()
  await expect(box.getByText('10月7日（週三）　15:00 – 16:00')).toBeVisible()
  await expect(box.getByText('今天還能試 2 次')).toBeVisible()

  await box.getByRole('button', { name: '明天有什麼行程？' }).click()
  await expect(box.getByText('明天 10/7（週三）有 2 個行程。')).toBeVisible()

  expect(api.assistantCalls).toBe(0)
  expect(api.csrfRejected).toEqual([])
})

test('按示範卡片的「加入行事曆」：不存檔，帶去登入（說明為什麼、游標停在帳號名稱）', async ({ page }) => {
  const api = await fakeApi(page, { loggedIn: false })
  await page.goto('/')

  await demo(page).getByRole('button', { name: '明天下午3點和 Amy 開會' }).click()
  await demo(page).getByRole('button', { name: '加入行事曆' }).click()

  await expect(page.getByRole('status')).toHaveText('登入後，助理的卡片就能直接加進行事曆')
  await expect(page.getByRole('heading', { name: '登入', level: 1 })).toBeVisible()
  await expect(page.getByLabel('帳號名稱')).toBeFocused()
  expect(api.saved).toEqual([])
})

test('第 4 次被擋（429）：顯示後端的說明，問題按鈕換成註冊；按了切到註冊；反例：前 3 次都看得到回答', async ({ page }) => {
  await fakeApi(page, { loggedIn: false })
  await page.goto('/')
  const box = demo(page)

  for (const [question, left] of [['明天下午3點和 Amy 開會', 2], ['明天有什麼行程？', 1], ['這週哪天下午有空？', 0]] as const) {
    await box.getByRole('button', { name: question }).click()
    await expect(box.getByText(`今天還能試 ${left} 次`)).toBeVisible()
  }
  await box.getByRole('button', { name: '明天有什麼行程？' }).click()

  await expect(box.getByText('今天的示範次數用完了，註冊後就能直接跟助理說話')).toBeVisible()
  await expect(box.getByRole('button', { name: '明天有什麼行程？' })).toHaveCount(0)

  await box.getByRole('button', { name: '註冊，直接跟助理說話' }).click()
  await expect(page.getByRole('heading', { name: '註冊', level: 1 })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('註冊之後就能直接跟助理說話，不限這幾題')
  await expect(page.getByLabel('帳號名稱')).toBeFocused()
})
