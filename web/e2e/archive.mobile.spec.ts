// 手機版的查看封存（#274，Pixel 7）。跟桌面版是同一個 ArchiveViewer，版面不同：
//   - 說明列在「月／週／日」下面，只有一個「上個月」（頁首），不用像桌面版那樣限定範圍
//   - 查詢畫面不是對話框，是整頁，左上角「返回」回到月曆
// 這些是先用瀏覽器打開手機版看過的。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

test('手機版：翻到 2 年以前的月份看得到說明，點進去選一天查得到，按返回回到月曆', async ({ page }) => {
  const api = await fakeApi(page, {
    archived: [{ id: 'a1', title: '看牙醫', start: '2024-03-05T09:00:00', end: '2024-03-05T10:30:00', category: 'HEALTH', location: '仁愛路診所', note: '帶健保卡' }],
  })
  await page.goto('/')
  await expect(page.getByText('結束超過 2 年的行程已自動封存，只能查看')).toBeHidden()
  for (let i = 0; i < 31; i++) await page.getByRole('button', { name: '上個月' }).click()
  await expect(page.getByRole('heading', { name: '3月' })).toBeVisible()
  await expect(page.getByText('結束超過 2 年的行程已自動封存，只能查看')).toBeVisible()

  await page.getByRole('button', { name: '查看封存的行程' }).click()
  await expect(page.getByRole('heading', { name: '封存的行程' })).toBeVisible()
  await page.getByRole('radio', { name: '一天' }).check()
  await page.getByLabel('日期', { exact: true }).fill('2024-03-05')
  await page.getByRole('button', { name: '查看', exact: true }).click()

  const item = page.getByRole('region', { name: '查到的封存行程' }).getByRole('listitem')
  await expect(item).toHaveCount(1)
  await expect(item).toContainText('看牙醫')
  await expect(item).toContainText('2024年3月5日（週二） 09:00 – 10:30')
  await expect(item).toContainText('仁愛路診所')
  expect(api.archiveQueries).toEqual([{ from: '2024-03-05', to: '2024-03-05' }])

  await page.getByRole('button', { name: '返回' }).click()
  await expect(page.getByRole('heading', { name: '封存的行程' })).toBeHidden()
  await expect(page.getByRole('heading', { name: '3月' })).toBeVisible()
})
