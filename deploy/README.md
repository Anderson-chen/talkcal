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

本機開發的 app（`backend/` 裡 `./gradlew bootRun`，8090）可以同時開著：它打主機上發佈出來的 8080，
用的是同一個模型容器。兩個 app 埠不同，可以並排比對。

瀏覽器開 http://localhost:18080：nginx 送前端的靜態檔，`/api` 轉給 `app:8090`（設定在 `web/nginx.conf`）。
app 的 18090 仍然開著，給 k6 和 curl 直接打，不多經過一層 nginx。

同一台機器上還可以多起一套 staging（28080、28090，自己的資料庫），見下面的[環境分層](#環境分層dev--staging--正式)。

## 第一次跑之前：建 `.env`

密碼和 key 不寫在程式碼裡（[#123](https://github.com/Anderson-chen/talkcal/issues/123)），放在這個資料夾的 `.env`，不進 git：

```bash
cp deploy/.env.example deploy/.env   # 再打開來填值
```

- `docker compose`、`./gradlew bootRun` 讀的都是這一份；雲端的 `deploy.sh` 也是把 SSM 裡的值寫到同一個位置
- 沒填 `DB_PASSWORD` 時兩邊都直接拒絕啟動，訊息會說要補什麼
- 已經有舊的 `postgres-data` volume（以前密碼寫死成 `talkcal`）：PostgreSQL 只在第一次初始化時讀密碼，`DB_PASSWORD` 要填 `talkcal`。
  想換成別的密碼，先改資料庫裡的，再改 `.env`：

  ```bash
  docker exec -it talkcal-postgres psql -U talkcal -d talkcal -c "ALTER ROLE talkcal PASSWORD '新密碼'"
  ```
- Google、GitHub 的 client id／secret 也填在這裡；沒填就只有帳密登入
- `.gitignore` 擋掉 `.env`，pre-push 的祕密掃描（gitleaks）再擋一次

## 一鍵部署

Gradle 指令都在 `backend/` 裡跑。它們只是快捷鍵：部署步驟寫在 [cd/apply.sh](../cd/README.md)，本機和 AWS 共用同一支，
Gradle 只多做一件事 —— `deployApp` 先跑測試。

```bash
./gradlew deploy                                 # 前後端都部署：先 deployApp，再 deployWeb
./gradlew deployWeb                              # 只部署前端（型別檢查在映像檔 build 裡做）
```

模型另外部署，**不在 `deploy` 裡**——很少改、換一次又貴（重 build 約十分鐘，換的期間所有請求都會失敗）：

```bash
./gradlew deployModels                           # 升級 llama.cpp、改了 compose 裡的模型參數之後用（預設等 600 秒）
```

每個服務分開換、都帶 `--no-deps`：不帶的話 `--build` 會沿著 `depends_on` 把別的服務也重 build、換掉
（部署 web 卻換上沒跑過測試的 app、部署 app 卻重啟模型）。理由見 [cd/README.md](../cd/README.md#為什麼每個服務分開換)。

## 一鍵部署 app

改了程式碼，要把新版放進容器：

```bash
./gradlew deployApp                              # 測試 → build 映像檔 → 換掉舊容器 → 等 healthy
./gradlew deployApp -x test                      # 跳過測試
./gradlew deployApp -PdeployWaitTimeout=600      # 模型第一次載入比較慢時，拉長等待（預設 300 秒）
```

只動 app，不碰模型和資料庫，所以第一次部署前要先 `docker compose up -d postgres llm-chat`。
前端也一樣：app 沒在跑的話，nginx 找不到 `app` 這個 upstream，web 起不來、會報 unhealthy。

## 環境分層：dev → staging → 正式

新版先放進一個跟正式一樣的環境（staging）驗過，正式才拿到**同一個 commit**（[#24](https://github.com/Anderson-chen/talkcal/issues/24)）。

| | dev（本機開發） | staging | 正式（本機 `local`／雲端 EC2） |
|---|---|---|---|
| 怎麼跑 | `./gradlew bootRun` + `npm run dev` | `cd/apply.sh staging` | `cd/apply.sh local`；EC2 是 `deploy.sh` |
| 誰觸發 | 自己 | watcher：master CI 綠燈就上 | watcher：**只拿 staging 部署成功過的 commit** |
| compose | 不用（直接跑在 Windows 上） | `compose.yaml` + `compose.staging.yaml` | `compose.yaml`（EC2 再疊 `infra/envs/1-ec2/` 的兩份） |
| compose 專案 | — | `talkcal-staging` | `talkcal-deploy` |
| Spring profile | 預設 | `docker` | `docker` |
| 網址 | :5173（vite）→ :8090 | :28080 → :28090 | :18080 → :18090；EC2 是 https |
| 資料庫 | 正式那個 postgres（`127.0.0.1:5432`） | **自己的**（volume `talkcal-staging_postgres-data`，不對主機開埠） | volume `talkcal-deploy_postgres-data` |
| 祕密 | `deploy/.env` | `deploy/.env.staging` | `deploy/.env`；EC2 從 SSM 拉 |
| 模型 | 主機的 8080 | 主機的 8080（**共用正式的**，見下面的限制） | `llm-chat`；EC2 是 Bedrock |
| log 格式 | 給人看的彩色文字 | JSON（ECS） | JSON（ECS），方便 Loki 拆欄位 |

### 設定怎麼依環境注入

照 [twelve-factor 的 config](https://12factor.net/config)：程式和映像檔不分環境，差異全部在外面注入。分成三層，越外層越優先：

1. **Spring profile**：「跑在容器裡」這種結構上的差異（服務名稱、log 格式）。`application.properties` 是 dev 的預設值，
   `application-docker.properties` 只覆蓋容器裡不一樣的那幾行。staging 和正式都是 `docker`：**不另開 `staging` profile**，
   staging 跟正式差一個 profile 檔，就是差一份沒在正式驗過的設定
2. **compose 疊加層**：「跟誰共存在哪台機器上」的差異（專案名稱、埠、映像檔 tag）。staging 是 `compose.staging.yaml`，EC2 是 `infra/envs/1-ec2/compose.*.yaml`
3. **`.env`**：祕密和網址。每個環境一份，不進 git

臨時要指到別的模型位址，不必改檔案：`-Dspring.ai.openai.chat.base-url=...` 或環境變數 `SPRING_AI_OPENAI_CHAT_BASEURL` 都比設定檔優先。

### 第一次起 staging

```bash
cp deploy/.env.example deploy/.env.staging
# 打開來：DB_PASSWORD 填一個跟正式不一樣的，最後加一行
#   TALKCAL_ENV=staging
cd/apply.sh staging                  # 起 postgres、app、web（模型共用正式那份，llm-chat 要先在跑）
cd/check-isolation.sh                # 把兩個環境合併後的設定攤開來比，有共用就失敗
```

`TALKCAL_ENV` 是防呆：忘了帶 `--env-file` 時 compose 會默默讀正式的 `deploy/.env`，`compose.staging.yaml` 要求這個只有
`.env.staging` 才有的變數，沒有就拒絕啟動。Google、GitHub 登入要的話另外申請一組（回呼網址是 `localhost:28080`），不填就只有帳密。

### 隔開了什麼

[cd/check-isolation.sh](../cd/check-isolation.sh) 比的是 `docker compose config` **合併之後**的結果：疊加層漏蓋一個欄位，看原始檔看不出來。

| 會撞到的東西 | 為什麼不吃專案名稱 | `compose.staging.yaml` 怎麼處理 |
|---|---|---|
| 專案名稱 | — | `name: talkcal-staging` 寫在檔案裡，不靠 `-p`：忘了帶也不會換掉正式的容器 |
| volume、網路 | 吃專案名稱，名字自動不同 | 不用改。資料庫因此是另一個 volume |
| `container_name` | `deploy/compose.yaml` 寫死了 | `!reset null`，改由專案名稱產生 |
| 主機埠 | 寫死；而且 `ports` 是清單，覆蓋時預設是**合併** | `!override` 整個換掉；postgres 用 `!reset []` 乾脆不開 |
| 自己 build 的映像檔 tag | 寫死 `:local` | 改成 `:staging`：不然 build staging 會把正式的 tag 指到還沒驗證的版本 |
| DB 密碼 | 從 `.env` 來 | 各自一份 `.env`，檢查會比兩邊是否相同（不印值） |

2026-10-16 驗證過（PR 裡有完整輸出）：把上表每一項拿掉一次，檢查都會失敗並指出共用了什麼；真的起一套 staging 跟正式並存時，
staging 的 app 裡 `postgres` 解析到 staging 自己的資料庫、解析不到 `talkcal-postgres`，在 staging 寫入一筆行程，正式的資料庫筆數不變，正式的容器沒有被重建。

### 晉升：staging → 正式

```
master CI ✅ ─→ watcher（staging）─→ cd/apply.sh staging ─→ GitHub Deployments：staging success
                                                                       │
                     watcher（local，PROMOTE_FROM=staging）◀───────────┘ 只拿 staging 最近一次 success 的 commit
                                   └─→ cd/apply.sh local ─→ GitHub Deployments：local success
```

- 晉升的單位是 **commit**，記錄在 GitHub Deployments：staging 失敗的版本不會被晉升，正式停在原地，等 staging 下一個成功的版本
- 兩個 watcher 各自一個部署用的 clone（watcher 會 checkout 要部署的 commit，共用一個 clone 會互相換掉工作目錄），設定見 [cd/README.md](../cd/README.md)
- 手動的 `./gradlew deploy` 不經過 watcher，不受晉升限制：它是 dev 的捷徑，不是正式的上線流程

### 選型理由

| 選項 | 為什麼沒選／選了 |
|---|---|
| 另開一台機器當 staging | 雲端要多付一台 EC2（平常關機也有 EBS、Elastic IP 的錢）；本機只有一台。**沒選**，列為已知限制 |
| 另寫一份完整的 `compose.staging.yaml` | 兩份遲早會不一樣，staging 驗過就不等於正式驗過。**沒選**，跟 EC2 一樣用疊加層 |
| 加一個 `staging` Spring profile | 多一份正式沒用過的設定檔。**沒選**，staging 和正式都用 `docker` |
| 同一台機器、另一個 compose 專案 + 疊加層 | **選這個**：零成本、真的跑得起來，差異只有「共存」非改不可的那幾行，而且有檢查擋住漏改 |
| 晉升靠 push（CI 綠燈後 GitHub Actions 依序部署） | 本機不讓 GitHub 連進來（[cd/README.md「為什麼是 pull」](../cd/README.md#為什麼是-pull)）。**選 pull**：正式的 watcher 自己去問 staging 成功了哪個版本 |

業界的共識（2026 年查的，見 PR）：映像檔 build 一次、同一份一路晉升，環境之間只差注入的設定；staging 的資源可以縮小，但架構要一樣；
k8s 上常見的分法是正式獨立、staging 跟其他非正式環境共用叢集、用 namespace 分開。

### 已知限制

| 限制 | 代價 | 搬到 EKS 之後怎麼補 |
|---|---|---|
| **跟正式同一台機器** | CPU、記憶體、Docker 都共用：staging 壓測會拖慢正式；機器掛了兩個一起掛 | staging 一個 namespace（加 ResourceQuota），正式獨立的節點群組或叢集（[2-eks](../infra/envs/2-eks/README.md)） |
| **模型共用正式的 llm-chat** | 12GB 的卡放不下兩份 Qwen3-8B。模型無狀態，資料不會混，但 staging 驗不到「換模型參數」這種改動，而且會搶正式的 slot | staging 的 Deployment 自己一個 llm-chat（GPU 節點可縮到 0），或兩邊都打 Bedrock |
| **晉升時正式會再 build 一次** | 晉升的是 commit，不是映像檔：正式在 checkout 之後重新 `--build`，理論上可能跟 staging 跑的不是同一個 byte | 等 [#25](https://github.com/Anderson-chen/talkcal/issues/25) 有版本號的映像檔，晉升改成同一個 tag；EKS 上就是 Argo CD 把同一個 image digest 從 staging 推到 prod |
| **「驗過」只到 healthcheck** | `compose up --wait` 只確認起得來；功能壞了（例如 #76 模型名稱送錯）照樣晉升 | 部署後的煙霧測試（`cd/README.md` 的 `verify.sh`，還沒做）通過才算 success |
| **staging 沒進觀測** | ops/ 的 Alloy 只看 `talkcal-deploy` 這個專案、只加入它的網路；staging 的 trace 送不出去（log 只會有 warning） | Alloy DaemonSet 收整個叢集，用 namespace 當 `env` 標籤 |
| **雲端（EC2）沒有 staging** | 只有一台、平常關機，多一台就是多一份錢。`host.docker.internal` 這招在 Linux 上也連不到只綁 127.0.0.1 的埠 | Terraform 用同一份程式、每個環境一個 state key 和 tfvars（`-backend-config="key=staging/..."`）；EKS 上則是同叢集另一個 namespace |
| **資料庫結構的晉升** | staging 先跑 Flyway migration，但它的資料是空的，大資料量下 migration 會不會鎖表驗不到 | 定期把正式的資料去識別化後複製一份給 staging |

## 容器之間怎麼溝通

同一個 compose 的服務自動在同一個網路（`talkcal-deploy_default`）上，Docker 內建 DNS 把服務名稱解析成容器 IP：

```bash
docker exec talkcal-app getent hosts llm-chat     # → 172.22.0.x  llm-chat
```

IP 每次重建容器都可能變，服務名稱不會——所以設定檔只寫名字。容器間走的是**容器內部的埠**，
跟 `ports:` 發佈到主機成幾號無關；`ports:` 只是給容器外面（你的瀏覽器、本機 bootRun）用的。
ops/ 的 Alloy 也不靠它：Alloy 加入了這組的網路，跟 app 一樣用服務名稱找人；app 送 trace 也是直接找 `alloy:4318`。

## 映像檔從哪來

**app**：`backend/Dockerfile`，build 用 Docker Hub 的 `gradle:9.2.1-jdk25`，執行用 `eclipse-temurin:25-jre`。
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
| `POSTGRES_PASSWORD` 從不進 git 的 `deploy/.env` 來 | Secret，app 用環境變數 `SPRING_DATASOURCE_PASSWORD` 拿 |

## 下一步（一次一件）

1. ~~Qwen 生成模型進容器~~ ✅（當時還有 bge-m3 embedding，RAG 拿掉後一起移除）
2. ~~模型檔改成 volume + 播種~~ ✅（bind mount 實測太慢，提前做了）
3. ~~app 也進容器，分環境，容器間用服務名稱溝通~~ ✅
4. ~~三個服務的 log 接進 Loki~~ ✅（見 `ops/README.md`）
5. 搬上一台 AWS EC2，同樣用 compose（[#57](https://github.com/Anderson-chen/talkcal/issues/57)）
6. 換成本機 k8s（kind）跑同一套（[#56](https://github.com/Anderson-chen/talkcal/issues/56)）
7. 部署到 EKS（[#58](https://github.com/Anderson-chen/talkcal/issues/58)）

5、7 的規劃見 [infra/](../infra/README.md)。

## 行程與對話記憶（PostgreSQL）

app 把行程（`calendar_event`）和 AI 助理的對話記憶（`calendar_assistant_message`，一則訊息一列、連工具呼叫一起存）
存進 `postgres` 容器，資料表由 Flyway 在 app 啟動時自動建（`backend/src/main/resources/db/migration`）。
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
