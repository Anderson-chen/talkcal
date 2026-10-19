# 0010. 後端用六角架構、功能優先的目錄，規則寫成 ArchUnit 測試

- 狀態：已採用
- 日期：2026-09-18（BuckPal 式目錄）、2026-09-21（功能優先、ArchUnit）
- 相關：[1f37297](https://github.com/Anderson-chen/talkcal/commit/1f37297)、[daf0ae8](https://github.com/Anderson-chen/talkcal/commit/daf0ae8)、[dae9042 用 ArchUnit 把架構規則變成測試](https://github.com/Anderson-chen/talkcal/commit/dae9042)

## 背景

專案的學習目標之一是 Clean Architecture：業務規則不綁框架、外部依賴（模型、資料庫）換得掉。
實際要換的東西確實出現過：檢索從關鍵字換成向量、llama.cpp 手寫 client 換成 Spring AI、本機模型換成 Bedrock。

Java 的 package 沒有依賴方向的概念：core 直接 import 並 new 一個 llama.cpp adapter，編譯器完全不反對（實測過）。
在這之前「core 不准依賴 adapter」只是口頭約定。

## 選項

目錄怎麼分：

| 選項 | 好處 | 代價 |
|---|---|---|
| 分層優先（全專案一個 `adapter/out`） | 起步最簡單 | 加第二個領域時檔案擠在共用資料夾；刪一個功能要到處找 |
| 功能優先（BuckPal、Spring Modulith 的組織方式） | 加功能是加一個資料夾、刪功能是刪一個資料夾；根 package 同時是 Spring Modulith 偵測模組的起點 | 模組之間的邊界要另外守 |

規則怎麼守：

| 選項 | 代價 |
|---|---|
| 靠 code review 記得 | 編譯器、測試都不會擋，架構一點一點爛掉 |
| ArchUnit 寫成測試 | 規則要寫、要維護；但違反就紅燈 |

## 決定

**`calendar` 一個模組，裡面是 `application/domain/{model,service}`、`application/port/{in,out}`、`adapter/{in,out}`；組裝根在 `app/`。
規則寫成 [ArchitectureTest](../../backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)，全部用 `noClasses()`「不准出現」的形式，每條帶 `because(...)`。**

守的規則包括：依賴只能由外往內、domain model 連自家的 port 都不認識、inbound 和 outbound adapter 互不認識、
只有組裝根（`app.config`、`app.job`）認識具體的 adapter、core 不准依賴 Spring / Jackson / OpenAPI / HTTP、模組之間互不 import、package 不繞成環。

規則本身驗過：放一個暫時的違規類別，編譯器放行，但該觸發的規則確實失敗，其他的沒有誤判（dae9042）。

## 代價

- 檔案和介面比較多：一個 use case 有 port.in 介面、service 實作、port.out，加上 adapter。
- 組裝根要手動接線（`@Configuration` 裡 `new`），不靠元件掃描。
- `@Transactional` 這類靠代理的註解在 `final` adapter 上用不了，改用 `TransactionTemplate`（見 [0009](0009-jdbc-client-not-jpa.md)）。

## 什麼時候重想

- 模組多到需要檢查模組之間的事件和公開 API → 接 Spring Modulith（根 package 已經照它的慣例放）。

## 證據

- [ArchitectureTest](../../backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)：每條規則和理由
- [README.md 的程式結構](../../README.md#程式結構)：目錄樹
- [ci/README.md](../../ci/README.md#架構檢查內容只寫一次平台只是入口)：CI 也照同一個道理分（`checks.sh` 是核心、各平台設定檔是 adapter）
