# deploy —— 在本機模擬雲端部署

把原本用 `.bat` 跑在 Windows 上的服務，**一次一個**搬進容器。之後上雲端，跑的就是這裡的定義。
跟 `ops/` 分開：`ops/` 是觀測（看別人），這裡是被部署的服務本身（被看的那些）。

## 現在搬到哪

| 服務 | 在哪跑 | 埠 |
|------|--------|----|
| Qwen3-8B 生成模型 | **容器**（`llm-chat`） | 8080 |
| talkcal app | **容器**（`app`） | 18090 |
| PostgreSQL（行程、AI 助理的對話記憶） | **容器**（`postgres`） | 5432（只綁 127.0.0.1） |
| 前端（nginx + Vue） | **容器**（`web`） | 18080 —— 用瀏覽器開這個 |

本機開發的 app（`./gradlew bootRun`，8090）可以同時開著：它打主機上發佈出來的 8080，
用的是同一個模型容器。兩個 app 埠不同，可以並排比對。

瀏覽器開 http://localhost:18080：nginx 送前端的靜態檔，`/api` 轉給 `app:8090`（設定在 `web/nginx.conf`）。
app 的 18090 仍然開著，給 k6 和 curl 直接打，不多經過一層 nginx。

## 一鍵部署

```bash
./gradlew deploy                                 # 前後端都部署：先 deployApp，再 deployWeb
./gradlew deployWeb                              # 只部署前端（型別檢查在映像檔 build 裡做）
```

模型另外部署，**不在 `deploy` 裡**——很少改、換一次又貴（重 build 約十分鐘，換的期間所有請求都會失敗）：

```bash
./gradlew deployModels                           # 升級 llama.cpp、改了 compose 裡的模型參數之後用（預設等 600 秒）
```

`deployWeb` 帶 `--no-deps`：不直接用 `docker compose up -d --build web`，是因為 `--build` 會沿著
`depends_on` 把 app 也重 build、換掉，繞過 `deployApp` 先跑測試那道關。

## 一鍵部署 app

改了程式碼，要把新版放進容器：

```bash
./gradlew deployApp                              # 測試 → build 映像檔 → 換掉舊容器 → 等 healthy
./gradlew deployApp -x test                      # 跳過測試
./gradlew deployApp -PdeployWaitTimeout=600      # 模型第一次載入比較慢時，拉長等待（預設 300 秒）
```

只動 app，不重 build 模型。定義在 `build.gradle` 最後的 `deployApp`，每一步為什麼這樣做見那裡的註解。

## 兩種環境怎麼分

同一份程式，靠 Spring profile 分環境。`application.properties` 是本機開發的預設值，
`application-docker.properties` 只覆蓋容器裡不一樣的那幾行（compose 用 `SPRING_PROFILES_ACTIVE=docker` 打開）：

| | 本機開發（預設） | 容器（`docker` profile） |
|---|---|---|
| 怎麼跑 | `./gradlew bootRun` | `docker compose up -d` |
| app 埠 | 8090 | 主機 18090 → 容器內 8090 |
| 模型位址 | `127.0.0.1:8080` | `llm-chat:8080` |
| 資料庫位址 | `127.0.0.1:5432`（用 deploy/ 這個 postgres） | `postgres:5432` |
| log 格式 | 給人看的彩色文字 | 一行一個 JSON（ECS），方便 Loki 拆欄位 |

臨時要指到別的模型位址，不必改檔案：`-Dspring.ai.openai.chat.base-url=...` 或環境變數 `SPRING_AI_OPENAI_CHAT_BASEURL` 都比設定檔優先。

## 容器之間怎麼溝通

同一個 compose 的服務自動在同一個網路（`talkcal-deploy_default`）上，Docker 內建 DNS 把服務名稱解析成容器 IP：

```bash
docker exec talkcal-app getent hosts llm-chat     # → 172.22.0.x  llm-chat
```

IP 每次重建容器都可能變，服務名稱不會——所以設定檔只寫名字。容器間走的是**容器內部的埠**，
跟 `ports:` 發佈到主機成幾號無關；`ports:` 只是給容器外面（你的瀏覽器、本機 bootRun）用的。
ops/ 的 Alloy 也不靠它：Alloy 加入了這組的網路，跟 app 一樣用服務名稱找人；app 送 trace 也是直接找 `alloy:4318`。

## 映像檔從哪來

**app**：repo 根目錄的 `Dockerfile`，build 用 Docker Hub 的 `gradle:9.2.1-jdk25`，執行用 `eclipse-temurin:25-jre`。
不用 `./gradlew`：Gradle 本體的下載點轉到 GitHub，本機實測 0.17 MB/s。第一次 build 約 1.5 分鐘，映像檔約 500MB。

**模型**：用一個**自己 build** 的映像檔 `talkcal/llama-server:b10964-cuda13.0.1`（`llama-server/Dockerfile`）。

為什麼不能直接拿 `C:\llm\llama.cpp` 用：容器裡是 Linux，`.exe` 和 `.dll` 是 Windows 格式，跑不了。
要的是 Linux 版 llama-server + Linux 版 CUDA 函式庫。模型檔（`.gguf`）是純資料，不用重新下載（見下一節）。

為什麼不拉官方的 `ghcr.io/ggml-org/llama.cpp:server-cuda`：本機連 ghcr 實測 0.17 MB/s，
Docker Hub 10 MB/s——從 Docker Hub 拉 CUDA 當底、自己編 llama.cpp 反而快。這也是上雲端的標準做法。

```bash
docker compose build        # 第一次或改了 Dockerfile 才需要；compose up 發現沒有 image 也會自動 build
docker compose build app    # 改了 app 的程式碼後，只重 build app
```

實測：拉 CUDA 底 ~6 分鐘 + 編譯 3.5 分鐘（只編 sm_89），共約 10 分鐘。映像檔 4.3GB。

## 模型檔怎麼進容器

`model-seed` 先把 `C:\llm\models` 的 `.gguf` 複製進具名 volume `models`，跑完就結束；模型服務等它成功才啟動，
從 volume 唯讀讀取。已經複製過的（大小相同）會跳過，所以只有第一次慢；有列在 `SHA256SUMS` 的會驗證內容。

為什麼不直接掛 `C:\llm\models`：實測 Windows → WSL2 檔案通道循序讀只有約 20 MB/s，
llama.cpp 預設的 mmap 零碎讀更慢——Qwen 5GB 載了 5 分鐘還卡在 275MB。

| | 直接掛 Windows 資料夾 | volume（現在） |
|---|---|---|
| Qwen 載入 | 5 分鐘以上沒載完 | **2.2 秒**（跟原生一樣） |
| 代價 | — | 第一次啟動多一次複製（約 2 分鐘），磁碟多存一份 5.6GB |

## 怎麼切換

```bash
# 原生 → 容器：先關掉 C:\llm 底下生成模型那支 .bat 的視窗（搶同樣的 8080，VRAM 也不夠放兩份）
# 本機的 bootRun 不用關：容器 app 在 18090，不會撞
cd deploy
docker compose up -d
docker compose ps           # STATUS 要等到 (healthy) 才能接請求；(health: starting) 代表模型還在載
docker compose logs -f      # 看載入過程，出現 "model loaded" 就載好了

# 容器 → 原生
docker compose down
# 再點兩下 C:\llm 底下生成模型那支 .bat

docker compose down -v      # 連模型 volume 一起清掉，下次啟動重新播種
```

## 對照雲端

| 這裡 | 雲端（k8s）上對應的東西 |
|------|------------------------|
| `image: talkcal/llama-server:b10964-cuda13.0.1` 釘版本 | Deployment 的 image tag，同樣要釘；image 推到同區的 registry |
| `deploy.resources...driver: nvidia` | `resources.limits: nvidia.com/gpu: 1` |
| `healthcheck` 打 `/health` | readinessProbe（載模型期間不導流量進來） |
| `start_period: 120s` | startupProbe / `initialDelaySeconds` |
| log 寫 stdout、不寫檔 | 平台的 log 收集器負責收 |
| `model-seed` 跑完才啟動模型服務 | initContainer（從 S3/GCS 拉模型） |
| 服務名稱 `llm-chat` 互相找 | k8s Service 的 DNS 名稱 |
| `SPRING_PROFILES_ACTIVE=docker` | Deployment 的 env，或 ConfigMap |
| 具名 volume `models` | PersistentVolumeClaim |
| `postgres` 容器 + `postgres-data` volume | 雲端託管的資料庫（RDS、Cloud SQL），不自己在 k8s 裡跑 |
| `POSTGRES_PASSWORD: talkcal` 寫在 compose | Secret，app 用環境變數 `SPRING_DATASOURCE_PASSWORD` 拿 |

## 下一步（一次一件）

1. ~~Qwen 生成模型進容器~~ ✅（當時還有 bge-m3 embedding，RAG 拿掉後一起移除）
2. ~~模型檔改成 volume + 播種~~ ✅（bind mount 實測太慢，提前做了）
3. ~~app 也進容器，分環境，容器間用服務名稱溝通~~ ✅
4. ~~三個服務的 log 接進 Loki~~ ✅（見 `ops/README.md`）
5. 換成本機 k8s（kind）跑同一套

## 行程與對話記憶（PostgreSQL）

app 把行程（`calendar_event`）和 AI 助理的對話記憶（`calendar_assistant_message`，一則訊息一列、連工具呼叫一起存）
存進 `postgres` 容器，資料表由 Flyway 在 app 啟動時自動建（`src/main/resources/db/migration`）。
本機 bootRun 的 app 也連同一個資料庫（`127.0.0.1:5432`），所以要先 `docker compose up -d postgres`。

直接看資料：

```bash
docker exec -it talkcal-postgres psql -U talkcal -d talkcal
```

```sql
-- 最近的對話和它們的訊息（30 天沒再說話的整段會被 AssistantMemoryCleanup 清掉）
SELECT conversation_id, position, type, left(content, 40), tool_calls IS NOT NULL AS called_tool
FROM calendar_assistant_message
ORDER BY created_at DESC, position;
```

`docker compose down` 不會刪資料；`down -v` 會連 `postgres-data` 一起清掉（模型的 volume 也會清，下次要重新播種）。
