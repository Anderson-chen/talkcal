// 助理回不了的時候，畫面上怎麼說、使用者可以怎麼做（#43）。
//
// 判斷只看後端分類好的 code 和 HTTP 狀態（契約見 #44 的 AssistantFailure），不看 error 的字面：
// 字面是給人看的，後端改個措辭不該讓前端的行為跟著變。
//
// 每一種的設計原則：
// - 說「發生什麼事」用使用者聽得懂的話，不說 503、熔斷、bulkhead
// - 說「可以怎麼做」：等幾秒再試、直接再試、改一下再送，或先用不靠模型的行程功能。
//   「行事曆照常可用」只在確定是模型出事時說；app 整個連不上時這樣說是騙人
// - 後端說了要等多久（retryAfterSeconds），就照著倒數；倒數完之前送不出去 —— 那段時間送了也一定失敗，
//   而且一群人同時狂按「再試」正是讓模型更起不來的原因
// - 不自動重送：模型一次要好幾秒、可能還要錢（Bedrock），使用者也可能已經不想問了；由使用者按「再試一次」
import { ApiError } from '../http'

export type FailureKind =
  | 'unavailable' // 503 MODEL_UNAVAILABLE：模型連不上，熔斷打開，這次沒叫模型
  | 'busy' //        503 MODEL_BUSY：同時在等模型的人滿了，這次沒叫模型
  | 'rate-limited' // 429：問得太快（nginx 的限流）
  | 'failed' //      502 ASSISTANT_FAILED：這次叫了模型、失敗了（模型出錯、逾時）
  | 'invalid' //     400：請求本身有問題（例如對話太長），原封不動再送也一樣
  | 'offline' //     沒拿到回應：網路斷了，或伺服器整台連不上
  | 'server-down' // 5xx 但不是 app 回的（沒有 code）：app 停了、正在重啟，nginx 自己回的錯誤頁
  | 'other'

export interface AssistantFailureView {
  kind: FailureKind
  // 顯示在對話裡的那句話
  text: string
  // 幾秒後才能再送。0 = 現在就可以
  waitSeconds: number
  // 原封不動再送一次有沒有意義（400 沒有：要先改）
  canRetry: boolean
}

// 後端沒給秒數時的保底，跟後端的預設一致（talkcal.model.circuit-breaker.wait-duration-in-open-state、bulkhead.retry-after、nginx 的 Retry-After）
const DEFAULT_WAIT = { unavailable: 30, busy: 5, 'rate-limited': 60 } as const

export function describeAssistantFailure(e: unknown): AssistantFailureView {
  if (!(e instanceof ApiError)) {
    return { kind: 'other', text: `助理暫時沒辦法回應：${e instanceof Error ? e.message : String(e)}`, waitSeconds: 0, canRetry: true }
  }
  const { status, code, retryAfterSeconds } = e.detail
  const wait = (fallback: number) => retryAfterSeconds ?? fallback

  if (code === 'MODEL_UNAVAILABLE') {
    return {
      kind: 'unavailable',
      text: 'AI 模型暫時連不上，助理先休息一下。行事曆的其他功能照常可用，可以直接按「新增行程」手動加。',
      waitSeconds: wait(DEFAULT_WAIT.unavailable), canRetry: true,
    }
  }
  if (code === 'MODEL_BUSY') {
    return {
      kind: 'busy',
      text: '現在問助理的人比較多，忙不過來。等幾秒再試一次。',
      waitSeconds: wait(DEFAULT_WAIT.busy), canRetry: true,
    }
  }
  if (status === 429) {
    return {
      kind: 'rate-limited',
      text: '問得太快了，先休息一下再問。',
      waitSeconds: wait(DEFAULT_WAIT['rate-limited']), canRetry: true,
    }
  }
  if (code === 'ASSISTANT_FAILED') {
    return {
      kind: 'failed',
      text: '這次助理沒有回成功（模型出錯或想太久）。可以直接再試一次。',
      waitSeconds: 0, canRetry: true,
    }
  }
  if (status === 400) {
    // 後端的話照實說（例如「對話太長了」），那句本身就是「該怎麼改」
    return { kind: 'invalid', text: `這句話送不出去：${e.message}`, waitSeconds: 0, canRetry: false }
  }
  if (status === null) {
    return { kind: 'offline', text: '連不上伺服器，請確認網路連線後再試一次。', waitSeconds: 0, canRetry: true }
  }
  if (status >= 500 && code === null) {
    return {
      kind: 'server-down',
      text: `伺服器暫時沒有回應（HTTP ${status}），可能正在更新。等一下再試一次。`,
      waitSeconds: 0, canRetry: true,
    }
  }
  return { kind: 'other', text: `助理暫時沒辦法回應：${e.message}`, waitSeconds: 0, canRetry: true }
}
