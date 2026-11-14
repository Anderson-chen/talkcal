// 防止「加入行事曆」「儲存行程」多存一筆一樣的行程，兩種重送各擋一道：
//
// 1. 連點：第一下還在送，第二下就略過（oneAtATime）。只靠按鈕的 disabled 擋不完：
//    Enter 送出表單、或兩下點得比畫面更新還快，都還是會呼叫到。
// 2. 送出去了、回應沒回來（逾時、502、斷線）：後端可能已經存好，前端分不出來。
//    retryKey 在第一次送之前取好 Idempotency-Key，重送時原樣再帶，後端看到同一把鍵就回原本那幾筆。

import type { Ref } from 'vue'

/** busy 為 true 時再呼叫直接回 undefined，不送第二次 */
export function oneAtATime<A extends unknown[], R>(busy: Ref<boolean>, action: (...args: A) => Promise<R>) {
  return async (...args: A): Promise<R | undefined> => {
    // 檢查和設成 true 中間沒有 await：JavaScript 單執行緒，第二下一定看得到第一下設的 true
    if (busy.value) return undefined
    busy.value = true
    try {
      return await action(...args)
    } finally {
      busy.value = false
    }
  }
}

/**
 * 內容沒變就一直是同一把鍵。內容變了（改了卡片、拿掉一張）就換新的：那是另一次新增，
 * 後端看到同一把鍵帶不同內容會回 422。
 *
 * 鍵只在記憶體裡，重新整理就沒了：那時畫面會重讀行事曆，使用者看得到上次其實存進去了。
 */
export function retryKey(newKey: () => string = () => crypto.randomUUID()) {
  let key: string | null = null
  let sentContent: string | null = null
  return {
    for(content: unknown): string {
      const text = JSON.stringify(content)
      if (key === null || text !== sentContent) {
        key = newKey()
        sentContent = text
      }
      return key
    },
    done() {
      key = null
      sentContent = null
    },
  }
}

export type RetryKey = ReturnType<typeof retryKey>
