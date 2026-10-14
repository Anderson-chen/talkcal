# 上線準備清單（Production Readiness）

上線之後要顧慮哪些事，哪些做完了、哪些還沒。這裡只列面向、目的和是否完成；每一項的證據、缺口和理由點面向名稱進去看。

- 怎麼分類、對照了哪些業界清單：[分類方法與參考資料](production-readiness/sources.md)
- 接下來先做哪些：[接下來的順序](production-readiness/next-steps.md)

最後盤點：2026-10-13

```
① 能不能上線    ② 怎麼上線      ③ 跑起來之後     ④ 出事的時候     ⑤ 長期活下去
  Ready           Release          Run              Respond          Evolve
```

只做到一部分的算「否」，做到哪裡寫在詳細內容裡。

## ① 能不能上線（Ready）

| 面向 | 目的 | 是否完成 |
|---|---|---|
| [分層與架構守護](production-readiness/ready.md#architecture-test) | 分層規則不靠人記，違反了測試就紅燈 | ✅ 是 |
| [整合測試用真的資料庫](production-readiness/ready.md#real-db-tests) | 測試通過就代表在真的 PostgreSQL 上也會通過 | ✅ 是 |
| [API 契約文件](production-readiness/ready.md#api-contract) | 前端和其他呼叫方知道 API 長什麼樣，改了會被發現 | ✅ 是 |
| [整個應用接起來的系統測試](production-readiness/ready.md#system-test) | 確認從 HTTP 到模型、資料庫整條路是通的 | ✅ 是 |
| [前端測試](production-readiness/ready.md#frontend-test) | 改畫面時不會把既有功能弄壞 | ❌ 否 |
| [AI 品質回歸（eval）](production-readiness/ready.md#ai-eval) | 改 prompt 或換模型之後，知道回答有沒有變差 | ❌ 否 |
| [認證與授權](production-readiness/ready.md#auth) | 只有本人看得到、改得到自己的資料 | ❌ 否 |
| [濫用防護（rate limit）](production-readiness/ready.md#rate-limit) | 一個人不能把 GPU 占滿，讓其他人都用不了 | ❌ 否 |
| [Agent 權限範圍](production-readiness/ready.md#agent-permissions) | 模型出錯或被誘導時，能造成的破壞有上限 | ✅ 是 |
| [Prompt injection](production-readiness/ready.md#prompt-injection) | 藏在資料裡的指令不能操控模型 | ❌ 否 |
| [模型輸出當成不可信資料](production-readiness/ready.md#output-handling) | 模型吐出的內容不能在使用者瀏覽器上執行 | ✅ 是 |
| [外部相依的逾時與重試](production-readiness/ready.md#timeouts) | 模型卡住時，app 不會跟著無限等下去 | ✅ 是 |
| [祕密管理](production-readiness/ready.md#secrets) | 密碼和 key 不寫在程式碼裡，可以換、不會外流 | ✅ 是 |
| [Container 加固](production-readiness/ready.md#container) | 容器被攻破時權限最小，關機時能好好收尾 | ✅ 是 |
| [相依套件與映像檔掃描](production-readiness/ready.md#dependency-scan) | 用到的套件有已知漏洞時會被發現 | ✅ 是 |
| [祕密掃描](production-readiness/ready.md#secret-scan) | 不小心 commit 的 key 在推出去之前就被擋下來 | ✅ 是 |
| [第三方映像檔與模型檔的來源](production-readiness/ready.md#supply-chain) | 跑的是確認過的那一份，不會被偷換 | ✅ 是 |

## ② 怎麼上線（Release）

| 面向 | 目的 | 是否完成 |
|---|---|---|
| [原始碼放在 GitHub](production-readiness/release.md#github) | 程式碼有備份、有歷史，別人看得到 | ✅ 是 |
| [CI](production-readiness/release.md#ci) | 每次改動都在乾淨環境自動驗證，不靠「我電腦上可以」 | ✅ 是 |
| [一鍵部署](production-readiness/release.md#deploy) | 部署不靠記步驟，每次都一樣 | ✅ 是 |
| [部署到 AWS：單台 EC2 + compose](production-readiness/release.md#cloud-deploy) | 服務真的在線上跑，別人連得到 | ✅ 是 |
| [本機 k8s（kind）](production-readiness/release.md#k8s-local) | 同一套映像檔在 k8s 上跑得起來，上 EKS 前先在本機練 | ❌ 否 |
| [部署到 EKS](production-readiness/release.md#eks) | 換版不停機、一台機器掛了不會全停 | ❌ 否 |
| [DB migration](production-readiness/release.md#migration) | schema 變更有版本、可重現，每個環境都一致 | ✅ 是 |
| [零停機的 schema 變更](production-readiness/release.md#zero-downtime-schema) | 改資料表時服務不用停 | ❌ 否 |
| [Graceful shutdown](production-readiness/release.md#graceful-shutdown) | 部署時正在處理的請求能做完，不被砍斷 | ✅ 是 |
| [Rollback](production-readiness/release.md#rollback) | 新版出問題時，幾分鐘內退回上一版 | ❌ 否 |
| [漸進式發布（canary）](production-readiness/release.md#canary) | 新版的問題只影響一小部分使用者 | ❌ 否 |
| [需求與待辦追蹤（GitHub Issues）](production-readiness/release.md#issues) | 每個改動都追得到為什麼做、做到哪、何時上線 | ✅ 是 |
| [分支策略](production-readiness/release.md#branching) | 大家用同一套方式改程式、合程式 | ✅ 是 |
| [Branch protection](production-readiness/release.md#branch-protection) | 沒過 CI 的程式碼進不了 `master`，誰都跳不過 | ✅ 是 |
| [程式碼格式與 lint](production-readiness/release.md#lint) | 風格自動統一，常見錯誤在編譯前就抓到 | ❌ 否 |
| [測試覆蓋率](production-readiness/release.md#coverage) | 知道哪些程式碼完全沒被測到 | ❌ 否 |
| [環境分層（staging）](production-readiness/release.md#staging) | 新版先在跟線上一樣的環境驗證過才上線 | ❌ 否 |
| [版本與 release tag](production-readiness/release.md#release-tag) | 說得出線上跑的是哪個版本 | ❌ 否 |
| [Hotfix 流程](production-readiness/release.md#hotfix) | 緊急修正又快又不跳過把關 | ❌ 否 |
| [用 AI 寫程式的把關](production-readiness/release.md#ai-guardrails) | AI 寫的程式跟人寫的過同一道關 | ✅ 是 |
| [給 AI 的專案規則](production-readiness/release.md#agents-md) | 寫法慣例跟著 repo 走，換人換工具都一樣 | ❌ 否 |
| [AI 改動的人工審查](production-readiness/release.md#ai-review) | AI 的每次改動都有人看過，而且留下紀錄 | ❌ 否 |
| [交付指標（DORA）](production-readiness/release.md#dora) | 用數字知道交付是變快還是變不穩 | ❌ 否 |

## ③ 跑起來之後（Run）

| 面向 | 目的 | 是否完成 |
|---|---|---|
| [指標、log、trace 三種訊號](production-readiness/run.md#three-signals) | 看得到系統在做什麼、哪裡不對 | ✅ 是 |
| [結構化 log 帶 trace id](production-readiness/run.md#structured-log) | log 能被查詢，也能對回是哪個請求印的 | ✅ 是 |
| [一個請求從頭串到尾](production-readiness/run.md#request-correlation) | 一個畫面看完一個請求經過哪裡、慢在哪一段 | ❌ 否 |
| [資料庫連線池](production-readiness/run.md#db-pool) | 分得出「API 變慢」是不是在排隊等資料庫連線 | ❌ 否 |
| [健康檢查](production-readiness/run.md#health) | 服務壞掉時會被發現、被重啟 | ✅ 是 |
| [Actuator 暴露面](production-readiness/run.md#actuator) | 管理用的端點不對外公開 | ❌ 否 |
| [壓測與容量](production-readiness/run.md#capacity) | 知道撐得住多少人，超過時會怎樣 | ✅ 是 |
| [SLO](production-readiness/run.md#slo) | 定義什麼叫「夠好」，有標準才知道該不該處理 | ❌ 否 |
| [成本](production-readiness/run.md#cost) | 知道每次請求花多少錢，能選自架還是雲端 | ❌ 否 |
| [產品指標](production-readiness/run.md#product-metrics) | 知道功能有沒有人用、有沒有用處 | ❌ 否 |
| [資料保存期限](production-readiness/run.md#retention) | 不需要的資料不會一直留著 | ✅ 是 |
| [Trace 取樣率](production-readiness/run.md#sampling) | 流量大時觀測的成本可控，關鍵的請求還留得住 | ❌ 否 |

## ④ 出事的時候（Respond）

| 面向 | 目的 | 是否完成 |
|---|---|---|
| [告警](production-readiness/respond.md#alerting) | 出事時有人被叫起來，不是等使用者來說 | ❌ 否 |
| [監控本身掛掉](production-readiness/respond.md#meta-monitoring) | 監控停了也會被發現，不會誤以為一切正常 | ❌ 否 |
| [Runbook](production-readiness/respond.md#runbook) | 告警響了，照著做就能處理，不靠記性 | ❌ 否 |
| [Postmortem](production-readiness/respond.md#postmortem) | 同一個問題不發生第二次 | ✅ 是 |
| [從使用者回報追到 trace](production-readiness/respond.md#user-report-trace) | 使用者說「剛剛怪怪的」時，找得到是哪一次 | ❌ 否 |
| [失敗時的使用者體驗](production-readiness/respond.md#failure-ux) | 壞掉時使用者知道發生什麼事、可以怎麼做 | ❌ 否 |
| [降級與熔斷](production-readiness/respond.md#degradation) | 模型掛了，其他功能照常可用 | ❌ 否 |
| [備份與還原](production-readiness/respond.md#backup) | 資料壞了或被誤刪時救得回來 | ❌ 否 |

## ⑤ 長期活下去（Evolve）

| 面向 | 目的 | 是否完成 |
|---|---|---|
| [根目錄 README](production-readiness/evolve.md#readme) | 第一次來的人知道這是什麼、怎麼跑起來 | ✅ 是 |
| [決策紀錄（ADR）](production-readiness/evolve.md#adr) | 之後的人知道當初為什麼這樣選 | ❌ 否 |
| [相依套件升級](production-readiness/evolve.md#dependency-upgrade) | 套件不會舊到升不動，安全修補跟得上 | ❌ 否 |
| [本機環境可重現](production-readiness/evolve.md#reproducible) | 換一台電腦也能把整套跑起來 | ❌ 否 |
| [並行修改](production-readiness/evolve.md#concurrency) | 兩個人同時改，不會悄悄蓋掉對方 | ❌ 否 |
| [冪等寫入](production-readiness/evolve.md#idempotency) | 重送或重試不會多出重複的資料 | ❌ 否 |
| [時區](production-readiness/evolve.md#timezone) | 行程時間在任何地方顯示都正確 | ✅ 是 |
| [隱私](production-readiness/evolve.md#privacy) | 使用者的對話不會外流到不該去的地方，要求刪除時刪得掉 | ❌ 否 |
| [授權（License）](production-readiness/evolve.md#license) | 用到的模型和套件都可以合法這樣用 | ❌ 否 |
| [資料歸檔](production-readiness/evolve.md#archiving) | 舊資料不占線上空間，需要時還找得回來 | ❌ 否 |
