## Summary

> TLDR: 一句話說改完之後變成怎樣

<!-- 一段說為什麼重要、解決什麼問題；細節留在 issue -->

## Depends On

<!-- 疊在別的 PR 上才寫：要等哪些 PR、建議的合併順序、這個 PR 自己的 commit 是哪幾個。沒有就整段拿掉 -->

## Evidence

<!-- 一點一件事，用 Before／After 對照；有截圖或數字就貼上。刻意沒做的寫在 Deliberately left out -->

- **Before:**
- **After:**

Deliberately left out:

## Test Plan

<!-- 審的人照著就能重現的步驟 -->

- [ ]

> Revert-proof: <!-- 把改動退回時，哪些測試或檢查會失敗（證明它們真的擋得住） -->

## Merge Danger

**Door:** <!-- two-way：revert 就回得去；one-way：資料遷移、刪資源這類退不回的 -->

**Blast Radius:** <!-- 影響到誰、哪些功能 -->

## AI Review

<!-- ai-review:start -->
<!-- 這段給人填。AI 開 PR 時原樣留著、一格都不勾，並加上 label ai-generated；
     人看完 diff 之後自己勾、自己填，沒填完 AI review 檢查是紅燈。
     不適用的項目也勾，在審查紀錄寫為什麼不適用。整個 PR 是人寫的：label 改成 human-written。
     流程和理由見 docs/ai-review.md -->

- [ ] diff 每個檔案都看過，說得出為什麼要改
- [ ] Evidence／Test Plan 至少一項自己重跑或打開證據核對過，不是只讀 AI 的描述
- [ ] 沒有超出 issue 範圍的改動（順手重構、不相干的檔案、多加的功能）
- [ ] 測試真的會擋：有反例，或確認過拿掉改動測試會紅
- [ ] 產品角度沒有洩漏內部設定（system prompt、工具名稱、祕密、內部網址）
- [ ] 新加的依賴、權限（workflow permissions、IAM、CORS…）都是必要的

審查到的 commit：<!-- 看過的那個 commit 的 SHA，至少 7 碼（gh pr view <PR> --json headRefOid）。之後再 push 就要重看 -->
審查紀錄：<!-- 一行：看了什麼、發現什麼、怎麼處理。例如「重跑了反例，紅燈如預期；請 AI 把 X 拆成另一個 PR」 -->
<!-- ai-review:end -->

Closes #

<!-- 上面填 issue 編號。合併時 GitHub 會自動關掉它；沒填的話 PR issue 檢查會紅燈 -->
