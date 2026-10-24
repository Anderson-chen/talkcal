// submitOnce.ts：連點不重送、失敗再按帶同一把冪等鍵（#51）。
// 「後端看到同一把鍵只存一次」在後端的 JdbcCalendarEventAdapterTest 對真的 PostgreSQL 測；這裡測前端有沒有把鍵帶對。
import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { oneAtATime, retryKey } from './submitOnce'

/** 一個要等外面放行才會結束的送出動作：用來模擬「第一下還在送」。 */
function pendingSave() {
  let finish!: (ok: boolean) => void
  let calls = 0
  const action = () => {
    calls++
    return new Promise<string>((resolve, reject) => {
      finish = (ok) => (ok ? resolve('saved') : reject(new Error('502')))
    })
  }
  return { action, finish: (ok: boolean) => finish(ok), calls: () => calls }
}

describe('oneAtATime', () => {
  it('連點兩下：第一下還在送，第二下不送（只呼叫一次）', async () => {
    const busy = ref(false)
    const save = pendingSave()
    const submit = oneAtATime(busy, save.action)

    const first = submit()
    const second = submit()
    save.finish(true)

    expect(await first).toBe('saved')
    expect(await second).toBeUndefined()
    expect(save.calls()).toBe(1)
  })

  it('反例：沒有包起來的話，連點兩下就是送兩次', () => {
    const save = pendingSave()

    save.action()
    save.action()

    expect(save.calls()).toBe(2)
  })

  it('送出期間 busy 是 true（按鈕綁它 disabled），結束後回到 false', async () => {
    const busy = ref(false)
    const save = pendingSave()
    const submit = oneAtATime(busy, save.action)

    const pending = submit()
    expect(busy.value).toBe(true)
    save.finish(true)
    await pending

    expect(busy.value).toBe(false)
  })

  it('失敗了也會放開：使用者看到錯誤可以再按一次，例外照樣往外丟', async () => {
    const busy = ref(false)
    const save = pendingSave()
    const submit = oneAtATime(busy, save.action)

    const failed = submit()
    save.finish(false)
    await expect(failed).rejects.toThrow('502')
    expect(busy.value).toBe(false)

    const retry = submit()
    save.finish(true)
    expect(await retry).toBe('saved')
    expect(save.calls()).toBe(2)
  })
})

describe('retryKey', () => {
  const counter = () => {
    let n = 0
    return () => `key-${++n}`
  }
  const dinner = [{ title: '吃飯', start: '2026-10-06T15:00', end: '2026-10-06T16:00' }]

  it('失敗後再送同樣的內容：帶同一把鍵（後端才認得出是重送）', () => {
    const keys = retryKey(counter())

    expect(keys.for(dinner)).toBe('key-1')
    expect(keys.for(dinner)).toBe('key-1')
  })

  it('內容改了（改了時間、拿掉一張卡片）：換一把新的 —— 不然後端會回 422', () => {
    const keys = retryKey(counter())

    keys.for(dinner)

    expect(keys.for([{ ...dinner[0], end: '2026-10-06T17:00' }])).toBe('key-2')
  })

  it('存好之後（done）再送，就是新的一次新增，換新鍵', () => {
    const keys = retryKey(counter())

    keys.for(dinner)
    keys.done()

    expect(keys.for(dinner)).toBe('key-2')
  })

  it('預設用 crypto.randomUUID()：格式是後端收的（英數和 -）', () => {
    expect(retryKey().for(dinner)).toMatch(/^[0-9a-f-]{36}$/)
  })
})
