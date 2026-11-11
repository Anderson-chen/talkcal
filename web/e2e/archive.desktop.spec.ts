// 查看封存的行程（#274）：月曆翻到 2 年以前的月份，看得到說明和入口；照一天或一段期間查，結果只能看。
// 「哪幾個月算封存」「日期合不合規」「時間怎麼寫」在 src/calendar/archive.test.ts 測透了，
// 「範圍內怎樣算重疊」是後端的規則（ArchivePeriodTest、EventArchiveFlowTest），假後端照同一套演。
// 這裡測的是接起來：說明出現在對的月份、送出去的日期對、查到的每個欄位都畫出來、沒有修改刪除、失敗了月曆照常。
import { expect, test, type Page } from '@playwright/test'
import { fakeApi, type FakeEvent } from './fakeApi'

test.beforeEach(async ({ page }) => {
  // 今天是 2026-10-06：2024-10-06 以前結束的行程都已經封存
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

const DENTIST: FakeEvent = {
  id: 'a1', title: '看牙醫', start: '2024-03-05T09:00:00', end: '2024-03-05T10:30:00', category: 'HEALTH', location: '仁愛路診所', note: '帶健保卡',
}
const MEETING: FakeEvent = {
  id: 'a2', title: '季度檢討', start: '2024-03-06T14:00:00', end: '2024-03-06T15:00:00', category: 'WORK', location: null, note: null,
}
// 跨月：3 月開始、4 月才結束
const TRIP: FakeEvent = {
  id: 'a3', title: '東京出差', start: '2024-03-30T09:00:00', end: '2024-04-02T18:00:00', category: 'WORK', location: '東京', note: null,
}

// 桌面版有兩個「上個月」（側欄小月曆、主畫面工具列），限定在主畫面裡找
async function flipBack(page: Page, months: number) {
  const previous = page.getByRole('main').getByRole('button', { name: '上個月' })
  for (let i = 0; i < months; i++) await previous.click()
}

async function openArchive(page: Page) {
  await page.getByRole('button', { name: '查看封存的行程' }).click()
  const dialog = page.getByRole('dialog', { name: '封存的行程' })
  await expect(dialog).toBeVisible()
  return dialog
}

test('翻到 2 年以前的月份才看得到「已自動封存」的說明；反例：2 年內的月份看不到', async ({ page }) => {
  await fakeApi(page)
  await page.goto('/')
  const notice = page.getByText('結束超過 2 年的行程已自動封存，只能查看')

  // 這個月、2 年內最早的那個月（2024-11）都沒有
  await expect(page.getByRole('heading', { name: '2026年10月' })).toBeVisible()
  await expect(notice).toBeHidden()
  await flipBack(page, 23)
  await expect(page.getByRole('heading', { name: '2024年11月' })).toBeVisible()
  await expect(notice).toBeHidden()
  await expect(page.getByRole('button', { name: '查看封存的行程' })).toBeHidden()

  // 再往前一個月：2024-10-06 以前結束的已經封存
  await flipBack(page, 1)
  await expect(page.getByRole('heading', { name: '2024年10月' })).toBeVisible()
  await expect(notice).toBeVisible()
  await expect(page.getByRole('button', { name: '查看封存的行程' })).toBeVisible()
})

test('選一天：查得到那天封存的行程，標題、時間、分類、地點、備註都在；只能看，沒有修改、刪除', async ({ page }) => {
  const api = await fakeApi(page, { archived: [DENTIST, MEETING] })
  await page.goto('/')
  await flipBack(page, 31)
  await expect(page.getByRole('heading', { name: '2024年3月' })).toBeVisible()
  const dialog = await openArchive(page)

  await dialog.getByRole('radio', { name: '一天' }).check()
  await dialog.getByLabel('日期', { exact: true }).fill('2024-03-05')
  await dialog.getByRole('button', { name: '查看', exact: true }).click()

  const found = dialog.getByRole('region', { name: '查到的封存行程' })
  await expect(found.getByRole('listitem')).toHaveCount(1)
  const item = found.getByRole('listitem')
  await expect(item).toContainText('看牙醫')
  await expect(item).toContainText('2024年3月5日（週二） 09:00 – 10:30')
  await expect(item).toContainText('健康')
  await expect(item).toContainText('仁愛路診所')
  await expect(item).toContainText('帶健保卡')
  await expect(found).not.toContainText('季度檢討')
  expect(api.archiveQueries).toEqual([{ from: '2024-03-05', to: '2024-03-05' }])

  // 只能看：查詢畫面裡沒有修改、刪除，前端也沒有送出任何會改東西的請求
  await expect(dialog.getByRole('button', { name: /修改|刪除/ })).toHaveCount(0)
  expect(api.saved).toEqual([])
  expect(api.updated).toEqual([])
  expect(api.deleted).toEqual([])
})

test('選一段期間：預設是看的那個月，期間內的都列出來，跨到下個月才結束的出差也在', async ({ page }) => {
  const api = await fakeApi(page, { archived: [DENTIST, MEETING, TRIP] })
  await page.goto('/')
  await flipBack(page, 31)
  const dialog = await openArchive(page)

  await expect(dialog.getByRole('radio', { name: '一段期間' })).toBeChecked()
  await expect(dialog.getByLabel('開始日期')).toHaveValue('2024-03-01')
  await expect(dialog.getByLabel('結束日期')).toHaveValue('2024-03-31')
  await dialog.getByRole('button', { name: '查看', exact: true }).click()

  const found = dialog.getByRole('region', { name: '查到的封存行程' })
  await expect(found.getByText('3 個行程')).toBeVisible()
  await expect(found.getByRole('listitem')).toHaveText([/看牙醫/, /季度檢討/, /東京出差/])
  await expect(found.getByRole('listitem').filter({ hasText: '東京出差' }))
    .toContainText('2024年3月30日（週六） 09:00 – 4月2日（週二） 18:00')
  expect(api.archiveQueries).toEqual([{ from: '2024-03-01', to: '2024-03-31' }])
})

test('期間裡沒有封存的行程：說沒有，不是空白一片', async ({ page }) => {
  await fakeApi(page, { archived: [DENTIST] })
  await page.goto('/')
  await flipBack(page, 31)
  const dialog = await openArchive(page)

  await dialog.getByLabel('開始日期').fill('2024-03-10')
  await dialog.getByRole('button', { name: '查看', exact: true }).click()

  await expect(dialog.getByText('這段期間沒有封存的行程')).toBeVisible()
})

test('反例：結束日期早於開始日期，畫面直接提示，不送出查詢', async ({ page }) => {
  const api = await fakeApi(page, { archived: [DENTIST] })
  await page.goto('/')
  await flipBack(page, 31)
  const dialog = await openArchive(page)

  await dialog.getByLabel('開始日期').fill('2024-03-10')
  await dialog.getByLabel('結束日期').fill('2024-03-01')
  await dialog.getByRole('button', { name: '查看', exact: true }).click()

  await expect(dialog.getByRole('alert')).toHaveText('結束日期不能早於開始日期')
  expect(api.archiveQueries).toEqual([])

  // 改成只看一天：上一次的提示不算數了，不能留在畫面上
  await dialog.getByRole('radio', { name: '一天' }).check()
  await expect(dialog.getByRole('alert')).toBeHidden()
})

test('封存讀不到（502）：查詢畫面說讀不到，關掉之後月曆上 2 年內的行程照常顯示', async ({ page }) => {
  const api = await fakeApi(page, {
    events: [{ id: 'e1', title: '產品週會', start: '2026-10-06T10:00:00', end: '2026-10-06T11:00:00', category: 'WORK', location: null, note: null }],
    archived: [DENTIST],
  })
  api.failNextArchive = true
  await page.goto('/')
  await flipBack(page, 31)
  const dialog = await openArchive(page)

  await dialog.getByRole('button', { name: '查看', exact: true }).click()
  await expect(dialog.getByRole('alert')).toHaveText('讀不到封存的行程：封存的資料暫時讀不到，請稍後再試')
  await expect(dialog.getByRole('region', { name: '查到的封存行程' })).toBeHidden()

  // 再按一次就好了（下一次假後端照常回）
  await dialog.getByRole('button', { name: '查看', exact: true }).click()
  await expect(dialog.getByRole('listitem')).toHaveText([/看牙醫/])

  await dialog.getByRole('button', { name: '關閉' }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('main').getByRole('button', { name: '今天' }).click()
  await expect(page.getByText('產品週會').first()).toBeVisible()
  await expect(page.getByRole('alert')).toBeHidden()
})
