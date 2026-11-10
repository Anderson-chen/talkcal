// 修改、刪除已經在行事曆上的行程（#259）：助理給的是卡片，按了確認才送 PUT、DELETE；行程詳情也能直接「修改」。
// 助理本身是假的（這裡直接決定它回哪張卡片），測的是前端：送出去的內容對不對、畫面跟著變、失敗時說得出原因。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi, type FakeEvent } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

const LUNCH: FakeEvent = {
  id: 'e1', title: '吃飯', start: '2026-10-06T11:00:00', end: '2026-10-06T12:00:00', category: 'SOCIAL', location: '拉麵店', note: null,
}
// 行程本身的欄位（不含 id）：PUT 的 expected、event 就是這個形狀
const fields = (e: FakeEvent): Omit<FakeEvent, 'id'> => ({
  title: e.title, start: e.start, end: e.end, category: e.category, location: e.location, note: e.note,
})
const LUNCH_FIELDS = fields(LUNCH)
const COFFEE = { ...LUNCH_FIELDS, title: '喝咖啡', category: 'PERSONAL' as const }

/** 助理下一句回這些卡片（後註冊的 route 先比對，蓋過 fakeApi 預設的新增卡片） */
async function assistantProposes(page: Page, cards: { changes?: unknown[]; removals?: unknown[] }) {
  await page.route('**/api/calendar/assistant', (route) => route.fulfill({
    status: 200,
    json: { conversationId: '00000000-0000-4000-8000-000000000259', reply: '請確認卡片。', proposals: [], changes: [], removals: [], ...cards },
  }))
}

async function ask(page: Page, text: string) {
  await page.getByPlaceholder('例如：下週二早上10點面試').fill(text)
  await page.getByPlaceholder('例如：下週二早上10點面試').press('Enter')
  await expect(page.getByText('請確認卡片。')).toBeVisible()
}

test('修改卡片按「確認修改」：送 PUT，帶著原本的樣子當 expected，行事曆上換成新的', async ({ page }) => {
  const api = await fakeApi(page, { events: [LUNCH] })
  await assistantProposes(page, { changes: [{ eventId: 'e1', before: LUNCH_FIELDS, after: COFFEE }] })
  await page.goto('/')
  await ask(page, '今天的吃飯改成喝咖啡')

  const card = page.locator('[data-card="change"]')
  await expect(card).toContainText('吃飯')
  await expect(card).toContainText('喝咖啡')
  await card.getByRole('button', { name: '確認修改' }).click()

  await expect(card.getByText('已修改')).toBeVisible()
  expect(api.updated).toEqual([{ id: 'e1', expected: LUNCH_FIELDS, event: COFFEE }])
  expect(api.saved).toEqual([])
  expect(api.events).toEqual([{ ...COFFEE, id: 'e1' }])
})

test('反例：卡片出現之後那筆被改過了（409）：卡片上顯示原因、還能取消，沒有蓋掉', async ({ page }) => {
  const api = await fakeApi(page, { events: [{ ...LUNCH, title: '吃午餐' }] })
  await assistantProposes(page, { changes: [{ eventId: 'e1', before: LUNCH_FIELDS, after: COFFEE }] })
  await page.goto('/')
  await ask(page, '今天的吃飯改成喝咖啡')

  const card = page.locator('[data-card="change"]')
  await card.getByRole('button', { name: '確認修改' }).click()

  await expect(card.getByRole('alert')).toContainText('剛被改過了')
  await expect(card.getByRole('button', { name: '確認修改' })).toBeVisible()
  expect(api.events[0].title).toBe('吃午餐')
  await card.getByRole('button', { name: '取消' }).click()
  await expect(card.getByText('已取消')).toBeVisible()
})

test('修改卡片按「自己調整」：表單帶著要改成的樣子，存檔送 PUT（不是新增）', async ({ page }) => {
  const api = await fakeApi(page, { events: [LUNCH] })
  await assistantProposes(page, { changes: [{ eventId: 'e1', before: LUNCH_FIELDS, after: COFFEE }] })
  await page.goto('/')
  await ask(page, '今天的吃飯改成喝咖啡')

  await page.locator('[data-card="change"]').getByRole('button', { name: '自己調整' }).click()
  const form = page.getByRole('dialog', { name: '修改行程' })
  await expect(form.getByLabel('標題')).toHaveValue('喝咖啡')
  await form.getByLabel('標題').fill('喝拿鐵')
  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(form).toBeHidden()
  await expect(page.locator('[data-card="change"]').getByText('已修改')).toBeVisible()
  expect(api.updated).toEqual([{ id: 'e1', expected: LUNCH_FIELDS, event: expect.objectContaining({ title: '喝拿鐵' }) }])
  expect(api.saved).toEqual([])
})

test('刪除卡片按「確定刪除」：送 DELETE，那筆從行事曆上不見；按「取消」什麼都不送', async ({ page }) => {
  const dinner: FakeEvent = { ...LUNCH, id: 'e2', title: '晚餐', start: '2026-10-06T19:00:00', end: '2026-10-06T20:00:00' }
  const api = await fakeApi(page, { events: [LUNCH, dinner] })
  await assistantProposes(page, { removals: [LUNCH, dinner] })
  await page.goto('/')
  await ask(page, '刪掉今天的吃飯和晚餐')

  const [lunchCard, dinnerCard] = [page.locator('[data-card="removal"]').nth(0), page.locator('[data-card="removal"]').nth(1)]
  await lunchCard.getByRole('button', { name: '確定刪除' }).click()
  await dinnerCard.getByRole('button', { name: '取消' }).click()

  await expect(lunchCard.getByText('已刪除')).toBeVisible()
  await expect(dinnerCard.getByText('已取消')).toBeVisible()
  expect(api.deleted).toEqual(['e1'])
  expect(api.events.map((e) => e.id)).toEqual(['e2'])
})

test('行程詳情按「修改」：表單帶著現在的樣子，改完送 PUT，詳情顯示改好的那筆', async ({ page }) => {
  const api = await fakeApi(page, { events: [LUNCH] })
  await page.goto('/')

  await page.getByText('吃飯').first().click()
  await page.getByRole('button', { name: '修改', exact: true }).click()
  const form = page.getByRole('dialog', { name: '修改行程' })
  await expect(form.getByLabel('標題')).toHaveValue('吃飯')
  await expect(form.getByLabel('地點')).toHaveValue('拉麵店')
  await form.getByLabel('標題').fill('吃拉麵')
  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(form).toBeHidden()
  expect(api.updated).toEqual([{
    id: 'e1', expected: LUNCH_FIELDS,
    event: { ...LUNCH_FIELDS, title: '吃拉麵', start: '2026-10-06T11:00', end: '2026-10-06T12:00' },
  }])
  expect(api.saved).toEqual([])
  await expect(page.getByRole('heading', { name: '吃拉麵' })).toBeVisible()
})
