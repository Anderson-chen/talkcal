# docs：文件索引

各資料夾怎麼運作寫在它自己的 README（見根目錄 [README](../README.md#目錄)）；跨資料夾的設計、決定、營運文件放這裡。

## 設計與決定

| 想知道 | 看哪裡 |
|---|---|
| 為什麼選 Spring AI、llama.cpp、JdbcClient、Alloy…：每個重大決定的選項和代價 | [adr/](adr/README.md) |
| 整體架構圖：本機、雲端的總覽，本機兩組 compose 怎麼接，CI/CD 每一關擋什麼 | [architecture.md](architecture.md) |
| AWS 階段一互動架構圖：每個元件由哪個檔案哪幾行決定、為什麼這樣選、業界怎麼做 | <https://anderson-chen.github.io/talkcal/>（原始檔 [architecture/index.html](architecture/index.html)） |
| 畫面設計稿和實作的差異 | [design/calendar/](design/calendar/README.md) |
| 資料表為什麼長這樣 | [backend/src/main/resources/db/migration](../backend/src/main/resources/db/migration)（每支 SQL 開頭都寫了理由） |
| 每個請求花多少錢、自架 GPU 和 Bedrock 在哪裡交叉 | [cost.md](cost.md) |
| 用到的模型、套件、映像檔能不能這樣用，CI 怎麼擋 GPL／AGPL；本專案為什麼是 MIT | [licenses.md](licenses.md) |

## 開發流程

| 想知道 | 看哪裡 |
|---|---|
| issue 貼哪個 `stage:`、`product:` label：上線準備的五段、產品範圍的八個面向怎麼分 | [classification.md](classification.md) |
| 每支自動化腳本什麼時候跑、誰叫它、對應哪個 `just` 指令 | [automation.md](automation.md) |
| 測試覆蓋率怎麼看、為什麼不設門檻 | [test-coverage.md](test-coverage.md) |
| 套件怎麼自動升級、升級 PR 怎麼審 | [dependency-updates.md](dependency-updates.md) |
| 版本號怎麼算、release tag 怎麼打 | [release.md](release.md) |

## 上線之後

| 想知道 | 看哪裡 |
|---|---|
| 怎樣才算「夠好」：SLI、SLO、錯誤預算 | [slo.md](slo.md) |
| 有哪些告警、各自什麼時候響 | [alerts.md](alerts.md) |
| 收到告警之後一步一步怎麼處理 | [runbooks/](runbooks/README.md) |
| 新版壞了怎麼退回上一版 | [rollback.md](rollback.md) |
| 出過的事故和事後檢討 | [postmortems/](postmortems/) |
| 故障、還原演練的紀錄和截圖 | [drills/](drills/) |
