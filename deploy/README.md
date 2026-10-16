# deploy —— 在本機模擬雲端部署

把原本用 `.bat` 跑在 Windows 上的服務，**一次一個**搬進容器。之後上雲端，跑的就是這裡的定義。
跟 `ops/` 分開：`ops/` 是觀測（看別人），這裡是被部署的服務本身（被看的那些）。

## 現在搬到哪

| 服務 | 在哪跑 | 埠 |
|------|--------|----|
| Qwen3-8B 生成模型 | **容器**（`llm-chat`） | 8080 |
| talkcal app | **容器**（`app`；部署時新版輪流起在 `app-green`，見[零停機部署與 canary](#零停機部署與-canary)） | 18090（`app-green` 是 18091） |
| PostgreSQL（行程、AI 助理的對話記憶） | **容器**（`postgres`） | 5432（只綁 127.0.0.1） |
| 前端（nginx + Vue） | **容器**（`web`） | 18080 —— 用瀏覽器開這個 |

本機開發的 app（`backend/` 裡 `./gradlew bootRun`，8090）可以同時開著：它打主機上發佈出來的 8080，
用的是同一個模型容器。兩個 app 埠不同，可以並排比對。

瀏覽器開 http://localhost:18080：nginx 送前端的靜態檔，`/api` 轉給 `app:8090`（設定在 `web/nginx.conf`）。
app 的 18090 仍然開著，給 k6 和 curl 直接打，不多經過一層 nginx。

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
./gradlew deployApp                              # 測試 → build 映像檔 → 新版起在另一個 slot、healthy 才切流量（見「零停機部署與 canary」）
./gradlew deployApp -x test                      # 跳過測試
./gradlew deployApp -PdeployWaitTimeout=600      # 模型第一次載入比較慢時，拉長等待（預設 300 秒）
```

只動 app，不碰模型和資料庫，所以第一次部署前要先 `docker compose up -d postgres llm-chat`。
前端也一樣：app 沒在跑的話，nginx 找不到 `app` 這個 upstream，web 起不來、會報 unhealthy。

## 零停機部署與 canary

[#20](https://github.com/Anderson-chen/talkcal/issues/20)。以前只有一個 app，部署是先關舊的、再開新的，
中間有一段沒人接的空窗（2026-10-12 量到 15–25 秒，見 [postmortem](../docs/postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)）。
現在 app 有兩個位置（slot），部署 app 時：

```
          web（nginx）  /api ──split_clients──┬──▶ app        （blue，舊版，正在接流量）
                                               └──▶ app-green  （green，新版先起在這）

1. 新版起在沒接流量的那個 slot，等 healthcheck 通過         docker compose up --wait app-green
2.（可選）canary：先切 10% 給新版，查 Mimir 的錯誤率和 p95    改寫 routing/app.conf → nginx -s reload
   ├─ 沒過：切回 100% 舊版，新版關掉（容器和 log 留著）        → 結束碼 1
   └─ 過了：往下
3. 全部切給新版                                             改寫 routing/app.conf → nginx -s reload
4. 等 5 秒，關掉舊版（graceful shutdown，手上的請求做完才結束）  docker compose stop app
```

下一次部署反過來：新版起在 app、關掉 app-green。兩個輪流，沒有固定誰是正式。

```bash
cd/apply.sh local app                          # 本機的 env 設了 APP_STRATEGY=bluegreen，app 自動走這條
CANARY_PERCENT=10 cd/apply.sh local app        # 加 canary（要先起 ops/，要查 Mimir）
cd/bluegreen.sh local --rollback               # 剛換上的新版有問題：切回上一個 slot（容器還在、映像檔是舊的，十幾秒）
cd/bluegreen.sh local --status                 # 現在 /api 轉給誰
```

`./gradlew deployApp` 呼叫的就是 `cd/apply.sh local app`，所以也是零停機。

### 驗證（2026-10-16，k6 每秒 20 個請求打 nginx）

在同一台機器上另起一組示範環境（[perf/canary/compose.demo.yaml](../perf/canary/compose.demo.yaml)，不碰正式那組），
用 [perf/canary.js](../perf/canary.js) 邊壓邊換。怎麼重跑見 [perf/README.md](../perf/README.md#canary邊壓邊換-app零停機與-canary-的反例)。

| 情境 | 請求數 | 失敗 | 說明 |
|---|---:|---:|---|
| 對照：舊做法（先關再開，`up --force-recreate app`） | 1801 | **106**（502） | 約 5 秒沒人接。這裡 app 閒著、起得快；postmortem 那次有 AI 助理的慢請求，空窗 15–25 秒 |
| blue/green（`cd/bluegreen.sh local`） | 2401 | **0** | 舊 app 結束碼 143（收到 SIGTERM 後自己做完才結束，不是被 SIGKILL 的 137） |
| `--rollback`（切回上一個 slot） | 1501 | **0** | 從下指令到切完 15 秒 |
| 反例：新版會回 500，canary 10% | 2938 | **57**（500），1.9% | canary 那 30 秒每 10 秒的失敗比例是 7.5%、9.0%、10.3%；第 30 秒的檢查算出 5xx 比例 100%，切回，之後 0 |
| 反例：canary 時查不到 Mimir | 3000 | 3（500） | 第一次跑時腳本查 Mimir 那行在 Windows 上有 bug（已修），等於查不到指標：當作沒過、1 秒內切回，不會「沒資料就放行」 |
| 正向對照：正常的新版走 canary 10%、90 秒 | 3525 | **0** | 三次檢查 5xx 都是 0，p95 沒變慢，通過、全切 |

兩件事也一起證明了：

- **session 存在資料庫**：舊做法那次 app 整個重啟，之後沒有任何 401；以前 session 在 app 的記憶體裡，重啟就全部登出
- **正常的新版不會被誤擋**：第一次跑正向對照時被擋了 —— 剛起的 JVM 還沒暖機，查行程的 p95 從 25ms 變 105ms，超過「舊版的 1.5 倍」。
  使用者感覺不到幾十毫秒，所以改成「超過 1.5 倍**而且**多 0.25 秒以上」才算變慢（`CANARY_P95_SLACK`）。這就是要做正向對照的原因

壞掉的新版怎麼做的：不改程式碼，用 [perf/canary/compose.broken.yaml](../perf/canary/compose.broken.yaml) 讓新版連到一個沒有表的 schema、
也不跑 migration（像是忘了帶 migration）。**healthcheck 照樣會過**（`/actuator/health` 只看連不連得上資料庫），
所以「等 healthy 才切」擋不住它，只有看真的流量的錯誤率才看得出來 —— 這正是 canary 要補的那一層。

### 為什麼這樣做

| 決定 | 選了 | 沒選 | 理由 |
|---|---|---|---|
| 怎麼不中斷 | blue/green：兩個 slot、nginx 切 | rolling（逐台換） | 單機只有一個 app，沒有「台」可以逐台換。兩個 slot 是單機上最小的「先起新的、再關舊的」 |
| 誰切流量 | 前面本來就有的 nginx（`split_clients`） | 多一層 Traefik、Envoy | nginx 已經在、已經轉 /api；改寫一個檔案再 `nginx -s reload` 就切了，reload 時舊的 worker 會把手上的請求做完 |
| 切換狀態放哪 | web 的具名 volume（`routing:`） | 主機上的檔案、重 build web | web 容器重建（部署新前端）時狀態還在，不會跑回 blue；同一台機器上的 staging 是另一個 compose 專案，各有各的 volume |
| 怎麼分流 | 依 `$request_id` 每個請求各抽 | 依使用者黏著（cookie） | 流量小時百分比比較準；k6 所有請求共用一個 session，依 cookie 分會變成 0% 或 100%。代價：同一個人的連續請求可能一次新版一次舊版，所以 API 要前後相容（[#18 零停機 schema](https://github.com/Anderson-chen/talkcal/issues/18)） |
| session | 存 PostgreSQL（Spring Session JDBC） | 黏著（sticky session） | 黏著的話，切換時被切走的人照樣登出，而且舊 slot 關掉時所有人都登出。存資料庫之後兩個 slot 都認得，部署也不登出任何人 |
| canary 誰判斷 | 腳本查 Mimir | 人看 Grafana 再按 | 見下一段 |
| 判斷什麼 | 5xx 比例 ≤ 1%（絕對）、p95 跟舊版比（相對＋寬容值） | p95 設絕對門檻 | 錯誤率不管哪個端點都該接近 0；延遲由 AI 助理的模型決定，絕對值天天不同，跟同一段時間、同一批流量的舊版比才公平 |
| 請求不夠多 | 當作沒過，切回 | 當作過了 | 「沒看到錯誤」不等於「沒有錯誤」。Flagger 也是這樣：查不到指標算失敗 |

**手動看 Grafana 還是腳本查指標**：選腳本。

| | 人看 Grafana | **腳本查 Mimir（選這個）** |
|---|---|---|
| 判斷標準 | 每次看的人心裡的感覺 | 寫在 `cd/bluegreen.sh` 裡，進 git、改了有紀錄 |
| 反應時間 | 人在不在電腦前 | 每 30 秒查一次，超標就切回（反例裡壞版本只活了 30 秒） |
| 自動部署（CD watcher）能不能用 | 不能，要有人守著 | 能 |
| 看得到全貌 | 看得到：延遲分布、log、trace 一起看 | 只看兩個數字；所以 Grafana「talkcal — Spring app」多一張「canary：每個 slot」的圖，用的是同一個算式，人要看也看得到 |
| 會不會誤判 | 會漏看 | 會被假訊號騙（例如上面的 JVM 暖機），要靠正向對照調門檻 |

做法跟 Argo Rollouts 的 AnalysisTemplate、Flagger 的 metric check 是同一件事：每隔一段時間查一次 PromQL，超過門檻幾次就中止。

### 限制

- **本機 CD 預設不開 canary**（`cd/envs/local.env` 只設 `APP_STRATEGY=bluegreen`）：本機沒有真的使用者，新版那 10% 等不到 `CANARY_MIN_REQUESTS` 個請求，每次都會判斷不了而切回。
  canary 的機制驗證過，要用時帶 `CANARY_PERCENT=10`
- **只有 app 零停機**。web 自己換的時候還是先關再開（nginx 收 SIGQUIT 會把手上的請求做完，但新的 nginx 起來前那一兩秒連不上）。要做的話得在 nginx 前面再一層（雲端是 Caddy）
- **EC2 還沒接上**：`infra/envs/1-ec2/deploy.sh` 還是直接 `compose up`，沒走 `cd/apply.sh`（`cd/envs/ec2.env` 還沒搬，見 [cd/README.md](../cd/README.md#還沒做的)）。搬過去就一起有了
- **18090 只連得到 blue**：k6 的 `deploy.js`、`knee.js` 預設直接打 app 的 18090。流量在 app-green 時那裡沒人；要量部署就打 nginx 的 18080（`BASE_URL=http://localhost:18080`）
- **部署的那幾十秒有兩個 JVM**：單機上新的 JVM 啟動會跟舊的搶 CPU。示範時同一台機器另一個 session 在跑 CI，那兩輪有幾秒請求變慢到好幾秒（沒有失敗）；沒有那個負載的兩輪最慢 192ms
- **回到更舊的版本不是這裡的事**：`--rollback` 只能切回上一個 slot（剛被換下來的那版）。指定任一舊版本見 [#19](https://github.com/Anderson-chen/talkcal/issues/19)；要指定映像檔 tag 等 [#25](https://github.com/Anderson-chen/talkcal/issues/25)

### 搬到 k8s／EKS 之後換成什麼

| 這裡 | k8s | 說明 |
|---|---|---|
| app、app-green 兩個 slot | Deployment 的 rolling update（`maxUnavailable: 0`、`maxSurge: 1`）＋ readiness probe | 零停機本身 k8s 內建，不必自己輪流（[#56](https://github.com/Anderson-chen/talkcal/issues/56) 用 kind 做） |
| nginx `split_clients` 依權重分流 | Gateway API 的 HTTPRoute `backendRefs[].weight` | ingress-nginx 2026-03 退役，k8s 上分流改用 Gateway API |
| `cd/bluegreen.sh` 的 canary 步驟 | [Argo Rollouts](https://github.com/argoproj/argo-rollouts)（2026-10 是 v1.10.1）的 `Rollout` canary steps ＋ [Gateway API plugin](https://github.com/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi)（v0.17.0）；或 [Flagger](https://github.com/fluxcd/flagger)（v1.45.0）直接改 HTTPRoute 權重 | 跟這支一樣：改權重、等、查指標。Argo Rollouts 配 ArgoCD、Flagger 配 Flux；已經規劃 ArgoCD（[cd/README.md](../cd/README.md#為什麼是-pull)）就選 Argo Rollouts |
| 腳本裡的 PromQL 和門檻 | `AnalysisTemplate`（Prometheus provider 指到 Amazon Managed Prometheus），`failureLimit`、`count` | 判斷的算式可以照搬 |
| `--rollback` | `kubectl argo rollouts abort` / `undo` | |
| session 存 PostgreSQL | 一樣（或 ElastiCache） | 多副本本來就需要 |

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
