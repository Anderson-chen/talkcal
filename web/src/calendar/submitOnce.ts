// 「加入行事曆」「儲存行程」按下去之後，怎麼保證不會多出一筆一樣的行程（#51）。
// 兩種重送，兩道防線：
//
// 1. 連點兩下：第一下還在送，第二下就不送 —— oneAtATime。按鈕也綁著 busy 變成 disabled，
//    但只靠 disabled 不夠：Enter 送出表單、或兩下點得比畫面更新還快，都還是會呼叫到。
// 2. 送出去了、回應卻沒回來（逾時、502、斷線）：畫面只看到失敗，使用者再按一次，這時後端其實已經存好了。
//    前端分不出來，只能讓後端認得出「這是同一次」：retryKey 在第一次送之前取好名字（Idempotency-Key），
//    重送時原樣再帶，後端看到同一個名字就回原本那幾筆，不再存一次。
//
// 純函式（不碰畫面、不碰 API），所以能在 Vitest 裡測；元件只負責接線。

import type { Ref } from 'vue'

/**
 * 包住一個送出的動作：busy 為 true 的時候再呼叫就直接略過（回 undefined），不會送第二次。
 * busy 在送出期間是 true，結束（成功或失敗）後回到 false —— 按鈕綁它 disabled、顯示「加入中…」。
 */
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
 * 一次「新增」的冪等鍵：內容沒變就一直是同一把（失敗後再按，帶的還是同一把），
 * 內容變了（使用者改了卡片、拿掉其中一張）就換一把新的 —— 後端看到同一把鍵帶不同內容會回 422，那是另一次新增，不是重送。
 * 存成功後呼叫 done()：之後再送就是新的一次。
 *
 * 鍵只活在記憶體裡：重新整理之後就沒了。那時候畫面會重新讀行事曆，使用者看得到上次其實存進去了，不會再按。
 */
export function retryKey(newKey: () => string = () => crypto.randomUUID()) {
  let key: string | null = null
  let sentContent: string | null = null
  return {
    /** 送這份內容要帶的鍵。 */
    for(content: unknown): string {
      const text = JSON.stringify(content)
      if (key === null || text !== sentContent) {
        key = newKey()
        sentContent = text
      }
      return key
    },
    /** 存好了：下一次送就換新的鍵。 */
    done() {
      key = null
      sentContent = null
    },
  }
}

export type RetryKey = ReturnType<typeof retryKey>
