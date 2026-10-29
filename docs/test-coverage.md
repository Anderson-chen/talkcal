# 測試覆蓋率

後端用 JaCoCo、前端用 Vitest 的 v8 coverage 量「測試跑過哪些程式碼」（[#23](https://github.com/Anderson-chen/talkcal/issues/23)）。
**只拿來找漏測的地方，不設門檻**：覆蓋率掉了 CI 不會紅。

## 怎麼產生、報告在哪

| | 指令 | 報告 |
|---|---|---|
| 全部（跟 CI 一樣，在容器裡） | `ci/run.sh` | 總數在 `ci/build/summary.md` 的「測試覆蓋率」；HTML 在 `ci/build/reports/jacoco/test/html/index.html`（後端）、`ci/build/web-coverage/index.html`（前端） |
| 只有後端 | `cd backend && ./gradlew test integrationTest jacocoTestReport` | `backend/build/reports/jacoco/test/html/index.html`（CSV 在同一層的 `jacocoTestReport.csv`，`ci/checks.sh` 讀它加總） |
| 只有前端 | `cd web && npm test`（`--coverage` 寫在 script 裡；`npm run test:watch` 不收集） | 終端機印總數；HTML 在 `web/coverage/index.html` |
| GitHub | 每次 CI 自動跑 | 總數貼在 Actions 結果頁的摘要；HTML 在 `coverage` 這個 artifact（成功、失敗都會上傳） |

範圍：

- **後端是單元測試＋整合測試合併的一份**。repository 的 SQL、Spring 組裝這些只有整合測試會走到，只看單元測試會把它們誤列為漏測。
  只跑 `./gradlew test jacocoTestReport` 也可以，報告就只有單元測試的部分。AI eval（`./gradlew eval`）不算、也不插樁：它量的是模型答得好不好，不是程式有沒有被走到。
- **前端只算 `src/**/*.ts`**。Vitest 只測純函式，`.vue` 元件交給 Playwright 用真的瀏覽器測（見 `web/vitest.config.ts` 開頭）；
  `.vue` 列進來只會是一排 0%，反而把真正的漏測淹掉。Playwright 跑到的程式不算進這份報告。
- 模型沒開時，要真模型的整合測試會跳過，那些程式在報告裡會是沒跑到 —— 看到 AI 相關的類別紅一片，先看摘要的「跳過的測試」。

## 為什麼不設門檻

設了門檻（例如「整體行覆蓋率低於 80% 就紅燈」），覆蓋率就從「找漏測的工具」變成「要達成的目標」，而這個數字很容易用沒意義的測試灌高：

- **覆蓋率只證明「跑過」，不證明「驗過」**。一支測試呼叫了方法但什麼都沒 assert，那幾行照樣算覆蓋。數字被拿來考核時，最省力的就是補這種測試。
- **門檻是整體的平均，擋不到真正要擋的**。新加的一段沒測的邏輯，被一堆早就測好的程式攤平，整體數字幾乎不動；
  反過來，刪掉一段測得很完整的舊程式，數字可能掉到門檻以下，CI 紅了卻沒有任何東西變差。
- **不同的程式值得的覆蓋率不一樣**。domain 的規則（行程時間、權限）每個分支都該測；Spring 的設定類別、OAuth 的轉址接線，
  單元測試很難測、整合測試或 E2E 才有意義。單一個數字表達不了這個差別。

2026 年常見的做法也是這個方向：Google 的〈Code Coverage Best Practices〉、Martin Fowler 的〈TestCoverage〉都主張覆蓋率用來找沒測的地方，
不當成品質指標；真的要用數字擋，比較多人改擋「這次改動的程式碼」（diff coverage）而不是整體。這個專案先不做：
目前一個人開發，每個 PR 由 review 看有沒有附測試就夠，等到需要時再加。

## 怎麼用報告找漏測

1. **打開 HTML，按「Missed」排序**（JaCoCo 的表頭可以點），從沒跑到最多的 package 往下看。
2. **先看 application**（`calendar.application`、`auth.application`，含底下的 `domain`）：這一層是業務規則，紅色的行幾乎都是該補的測試。
   adapter 的紅色要分辨：controller 的錯誤處理分支該補；`app.config` 的組裝、純接線的程式可以略過。
3. **看分支，不只看行**。JaCoCo 在行號旁的菱形：黃色是「只走過其中一邊」，滑上去會說幾個分支沒走到 —— 常見是 `if` 只測了成立的那邊、
   例外的那條路沒人測。行全綠、分支有黃，是最容易漏掉的地方。
4. **前端看沒有任何測試 import 的 `.ts`**：那些檔案整份 0%（`coverage.include` 刻意把它們列出來）。
   `use*.ts` 這類 composable 跟 Vue 綁在一起，判斷一下是該抽純函式出來測，還是 E2E 已經測到了。
5. **找到之後補測試，不是補到數字好看為止**：問「這段如果寫錯，哪支測試會紅？」，答不出來才補。

覆蓋率看不出測試有沒有在驗東西。要量這件事要靠 mutation testing（Java 的 PIT、JS 的 Stryker：故意改壞程式碼，看測試抓不抓得到），之後有需要再加。
