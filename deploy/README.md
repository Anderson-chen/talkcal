# deploy —— 在本機模擬雲端部署

把原本用 `.bat` 跑在 Windows 上的服務，**一次一個**搬進容器。之後上雲端，跑的就是這裡的定義。
跟 `ops/` 分開：`ops/` 是觀測（看別人），這裡是被部署的服務本身（被看的那些）。

## 現在搬到哪

| 服務 | 在哪跑 | 埠 |
|------|--------|----|
| Qwen3-8B 生成模型 | **容器**（`llm-chat`） | 8080 |
| bge-m3 embedding | **容器**（`llm-embedding`） | 8081 |
| eat app | 原生（`./gradlew bootRun`） | 8090 |

埠刻意跟原生一樣，所以 app 和 `ops/` 的 Prometheus 都不用改設定。

## 映像檔從哪來

兩個模型共用一個**自己 build** 的映像檔 `eat/llama-server:b10964-cuda13.0.1`（`llama-server/Dockerfile`）。

為什麼不能直接拿 `C:\llm\llama.cpp` 用：容器裡是 Linux，`.exe` 和 `.dll` 是 Windows 格式，跑不了。
要的是 Linux 版 llama-server + Linux 版 CUDA 函式庫。模型檔（`.gguf`）是純資料，不用重新下載（見下一節）。

為什麼不拉官方的 `ghcr.io/ggml-org/llama.cpp:server-cuda`：本機連 ghcr 實測 0.17 MB/s，
Docker Hub 10 MB/s——從 Docker Hub 拉 CUDA 當底、自己編 llama.cpp 反而快。這也是上雲端的標準做法。

```bash
docker compose build        # 第一次或改了 Dockerfile 才需要；compose up 發現沒有 image 也會自動 build
```

實測：拉 CUDA 底 ~6 分鐘 + 編譯 3.5 分鐘（只編 sm_89），共約 10 分鐘。映像檔 4.3GB。

## 模型檔怎麼進容器

`model-seed` 先把 `C:\llm\models` 的 `.gguf` 複製進具名 volume `models`，跑完就結束；兩個模型服務等它成功才啟動，
從 volume 唯讀讀取。已經複製過的（大小相同）會跳過，所以只有第一次慢；有列在 `SHA256SUMS` 的會驗證內容。

為什麼不直接掛 `C:\llm\models`：實測 Windows → WSL2 檔案通道循序讀只有約 20 MB/s，
llama.cpp 預設的 mmap 零碎讀更慢——Qwen 5GB 載了 5 分鐘還卡在 275MB。

| | 直接掛 Windows 資料夾 | volume（現在） |
|---|---|---|
| Qwen 載入 | 5 分鐘以上沒載完 | **2.2 秒**（跟原生一樣） |
| 代價 | — | 第一次啟動多一次複製（約 2 分鐘），磁碟多存一份 5.6GB |

## 怎麼切換

```bash
# 原生 → 容器：先關掉 C:\llm 底下兩個 .bat 的視窗（搶同樣的 8080/8081，VRAM 也不夠放兩份）
cd deploy
docker compose up -d
docker compose ps           # STATUS 要等到 (healthy) 才能接請求；(health: starting) 代表模型還在載
docker compose logs -f      # 看載入過程，出現 "model loaded" 就載好了

# 容器 → 原生
docker compose down
# 再點兩下 C:\llm 底下兩個 .bat

docker compose down -v      # 連模型 volume 一起清掉，下次啟動重新播種
```

## 對照雲端

| 這裡 | 雲端（k8s）上對應的東西 |
|------|------------------------|
| `image: eat/llama-server:b10964-cuda13.0.1` 釘版本 | Deployment 的 image tag，同樣要釘；image 推到同區的 registry |
| `deploy.resources...driver: nvidia` | `resources.limits: nvidia.com/gpu: 1` |
| `healthcheck` 打 `/health` | readinessProbe（載模型期間不導流量進來） |
| `start_period: 120s` | startupProbe / `initialDelaySeconds` |
| log 寫 stdout、不寫檔 | 平台的 log 收集器負責收 |
| `model-seed` 跑完才啟動模型服務 | initContainer（從 S3/GCS 拉模型） |
| 具名 volume `models` | PersistentVolumeClaim |

## 下一步（一次一件）

1. ~~Qwen 生成模型、bge-m3 embedding 進容器~~ ✅
2. ~~模型檔改成 volume + 播種~~ ✅（bind mount 實測太慢，提前做了）
3. app 也進容器，所有位址改由環境變數給
4. 換成本機 k8s（kind）跑同一套——到時要決定 embedding 跟 chat 塞同一個 Pod 共用 GPU，還是改跑 CPU
