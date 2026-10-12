# ⑤ 長期活下去（Evolve）

[← 回上線準備清單](../production-readiness.md)

<a id="readme"></a>
### 根目錄 README ✅

[README.md](../../README.md)：產品是什麼、作品集想展示什麼、架構圖、怎麼跑起來、文件地圖。

<a id="adr"></a>
### 決策紀錄（ADR） ❌（部分做到）

理由都寫在程式碼註解和各目錄的 README 裡，但沒有一個集中的地方說明「為什麼選 Spring AI、llama.cpp、JDBC」。

<a id="dependency-upgrade"></a>
### 相依套件升級 ❌

沒有 Renovate 或 Dependabot。

<a id="reproducible"></a>
### 本機環境可重現 ❌（部分做到）

測試和 CI 都在容器裡跑，換一台機器結果也一樣。但模型檔的路徑寫死成 `C:\llm\models`（[compose](../../deploy/compose.yaml)），整套服務只能在 Windows 上照原樣起來。

<a id="concurrency"></a>
### 並行修改 ❌（部分做到）

行程目前只能新增和刪除，沒有修改的 API，所以還沒有「兩個分頁互相覆蓋」的問題；之後加修改功能時，`calendar_event` 要先加 version 欄位。助理的對話記憶是整段刪掉再重新寫入，同一段對話同時送出兩句的話，晚存的會蓋掉早的（[V7](../../backend/src/main/resources/db/migration/V7__create_calendar_assistant_message.sql) 寫明接受這個代價）。

<a id="idempotency"></a>
### 冪等寫入 ❌

按兩次「加入」或 agent 重試，可能多出一筆一樣的行程。

<a id="timezone"></a>
### 時區 ✅

[V2](../../backend/src/main/resources/db/migration/V2__create_calendar_events.sql) 寫了為什麼用不帶時區的 `TIMESTAMP`。

<a id="privacy"></a>
### 隱私 ❌

沒有確認對話內容會不會進到 Loki 和 Tempo（`LLM02` Sensitive Information Disclosure；Mercari 清單的 “Non-sensitive log”），也沒有使用者要求刪除資料時的流程。

<a id="license"></a>
### 授權（License） ❌

沒有整理 Qwen3 和前端套件的授權。

<a id="archiving"></a>
### 資料歸檔 ❌

舊資料目前是直接刪掉，還沒有「壓縮後放到物件儲存」的流程。
