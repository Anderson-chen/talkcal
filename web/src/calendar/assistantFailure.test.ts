// 助理回不了時怎麼說、等多久、能不能再試（#43）。判斷只看 code 和 HTTP 狀態，契約是 #44 的 AssistantFailure。
import { describe, expect, it } from 'vitest'
import { ApiError } from '../http'
import { describeAssistantFailure } from './assistantFailure'

const failure = (status: number | null, code: string | null = null, retryAfterSeconds: number | null = null, message = '後端的話') =>
  new ApiError(message, { status, code, retryAfterSeconds })

describe('describeAssistantFailure', () => {
  it('模型連不上（503 MODEL_UNAVAILABLE）：照後端給的秒數倒數，並說行事曆還能用', () => {
    const view = describeAssistantFailure(failure(503, 'MODEL_UNAVAILABLE', 30))
    expect(view).toMatchObject({ kind: 'unavailable', waitSeconds: 30, canRetry: true })
    expect(view.text).toContain('行事曆的其他功能照常可用')
  })

  it('忙碌（503 MODEL_BUSY）：等 5 秒', () => {
    expect(describeAssistantFailure(failure(503, 'MODEL_BUSY', 5))).toMatchObject({ kind: 'busy', waitSeconds: 5, canRetry: true })
  })

  it('問太快（nginx 的 429，body 沒有 code）：照 Retry-After 等', () => {
    expect(describeAssistantFailure(failure(429, null, 60))).toMatchObject({ kind: 'rate-limited', waitSeconds: 60, canRetry: true })
  })

  it('後端沒給秒數時，用跟後端預設一樣的保底', () => {
    expect(describeAssistantFailure(failure(503, 'MODEL_UNAVAILABLE')).waitSeconds).toBe(30)
    expect(describeAssistantFailure(failure(503, 'MODEL_BUSY')).waitSeconds).toBe(5)
    expect(describeAssistantFailure(failure(429)).waitSeconds).toBe(60)
  })

  it('這次叫了模型、失敗（502 ASSISTANT_FAILED）：不用等，可以馬上再試', () => {
    expect(describeAssistantFailure(failure(502, 'ASSISTANT_FAILED'))).toMatchObject({ kind: 'failed', waitSeconds: 0, canRetry: true })
  })

  it('請求本身有問題（400）：照實說後端的話，不給原封不動再送', () => {
    const view = describeAssistantFailure(failure(400, 'INVALID_REQUEST', null, '對話太長了，請按「清空」開始新的對話'))
    expect(view).toMatchObject({ kind: 'invalid', canRetry: false, waitSeconds: 0 })
    expect(view.text).toContain('對話太長了')
  })

  it('沒拿到回應（網路斷了）：說連不上，可以再試；不說行事曆還能用', () => {
    const view = describeAssistantFailure(failure(null))
    expect(view).toMatchObject({ kind: 'offline', waitSeconds: 0, canRetry: true })
    expect(view.text).not.toContain('照常可用')
  })

  it('5xx 但沒有 code（app 停了，nginx 自己回的）：伺服器沒回應，附狀態碼', () => {
    const view = describeAssistantFailure(failure(504))
    expect(view).toMatchObject({ kind: 'server-down', canRetry: true })
    expect(view.text).toContain('HTTP 504')
    expect(view.text).not.toContain('照常可用')
  })

  it('反例：判斷看 code 不看字面 —— 後端改了措辭，行為不變；502 沒有 code 不當成模型失敗', () => {
    expect(describeAssistantFailure(failure(503, 'MODEL_UNAVAILABLE', 30, '完全不同的一句話')).kind).toBe('unavailable')
    expect(describeAssistantFailure(failure(502, null, null, 'AI 助理失敗：模型沒有回應')).kind).toBe('server-down')
  })

  it('反例：不是後端的錯誤（程式自己的例外）不倒數', () => {
    expect(describeAssistantFailure(new TypeError('x is undefined'))).toMatchObject({ kind: 'other', waitSeconds: 0 })
  })
})
