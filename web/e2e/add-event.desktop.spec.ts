// 新增行程：從按「新增行程」到它出現在月曆上，整條流程在真的瀏覽器裡跑一遍。
// EventForm 的規則（驗證、跨夜、去空白）在 src/calendar/eventDraft.test.ts 測透了，
// 這裡測的是「接起來」：表單打得開、填得進去、送出去的資料對、存好之後畫面跟著變。
import { expect, test } from '@playwright/test'
import { fakeApi } from './fakeApi'

test.beforeEach(async ({ page }) => {
  // 固定瀏覽器的「現在」：行事曆到處用到今天，不固定的話換一天跑結果就不一樣
  await page.clock.setFixedTime(new Date('2026-10-06T11:30:00+08:00'))
})

test('新增一筆行程：送出填的資料，存好之後出現在月曆上', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')

  // 找元素都用使用者看得到的東西：按鈕上的字、欄位的 label、對話框的標題。
  // exact：name 預設是「包含」就算 —— 月曆每一格都有「在10月6日新增行程」，不加會找到 43 個按鈕。
  // 找到不只一個時 Playwright 直接報錯、不會隨便挑一個點（strict mode），點錯了才不會沒人發現
  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await expect(form).toBeVisible()

  await form.getByLabel('標題').fill('產品週會')
  await form.getByLabel('地點').fill('三樓會議室')
  await form.getByRole('button', { name: '儲存行程' }).click()

  // expect(...).toBeHidden() 會自己重試等到成立（預設最多 5 秒）：不用自己等畫面更新
  await expect(form).toBeHidden()
  expect(api.saved).toEqual([
    [
      {
        title: '產品週會',
        start: '2026-10-06T10:00',
        end: '2026-10-06T11:00',
        category: 'WORK',
        location: '三樓會議室',
        note: null,
      },
    ],
  ])
  // 存好之後畫面重讀，新的那筆出現在月曆上（還會打開它的詳情，所以可能不只一處，取第一個）
  await expect(page.getByText('產品週會').first()).toBeVisible()
})

test('沒填標題就按儲存：表單裡顯示錯誤，而且根本沒有送出', async ({ page }) => {
  const api = await fakeApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })

  await form.getByRole('button', { name: '儲存行程' }).click()

  // role="alert"：螢幕閱讀器會念出來的錯誤訊息。用 role 找，順便確認它真的被標成 alert
  await expect(form.getByRole('alert')).toHaveText('請輸入行程標題')
  await expect(form).toBeVisible()
  // 反例：驗證擋住了，就一個請求都不該送出去
  expect(api.saved).toEqual([])
})

test('存檔失敗：顯示後端回的錯誤，表單不關、填的東西還在，可以再按一次', async ({ page }) => {
  const api = await fakeApi(page)
  api.failNextSave = '伺服器暫時沒有回應'
  await page.goto('/')
  await page.getByRole('button', { name: '新增行程', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增行程' })
  await form.getByLabel('標題').fill('產品週會')

  await form.getByRole('button', { name: '儲存行程' }).click()

  await expect(form.getByRole('alert')).toHaveText('伺服器暫時沒有回應')
  await expect(form.getByLabel('標題')).toHaveValue('產品週會')
  // 存檔中按鈕會變成「儲存中…」並停用；失敗之後要變回來，不然使用者沒辦法再試
  const save = form.getByRole('button', { name: '儲存行程' })
  await expect(save).toBeEnabled()

  // 再按一次：這次後端正常了，存得進去
  await save.click()
  await expect(form).toBeHidden()
  expect(api.saved).toHaveLength(2)
})
