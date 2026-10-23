# deploy/k8s —— 同一套服務，改用 k8s 跑（#56）

`deploy/compose.yaml` 翻成 k8s 的設定。先在本機的 kind 叢集上跑，之後 EKS（[#58](https://github.com/Anderson-chen/talkcal/issues/58)）沿用同一份 base，
只換 overlay。映像檔跟 compose 是**同一個**（`backend/Dockerfile`、`web/Dockerfile`），沒有 k8s 專用的版本。

## 結構

```
deploy/k8s/
├── base/                       kind 和 EKS 都一樣的部分
│   ├── app.yaml                Deployment ×2 + Service；三種探針、rolling update、150 秒關機等待
│   └── web.yaml                Deployment ×2 + Service
├── components/                 可選的零件，overlay 挑要哪幾個
│   ├── llm-chat-gpu/           模型在叢集裡跑：GPU、initContainer 從 S3 拉模型到 PVC（給 EKS）
│   ├── llm-chat-external/      模型在叢集外：Service 只是個名字，指到外面的 llama-server（給 kind）
│   └── postgres/               叢集裡的 PostgreSQL（給 kind；EKS 用 RDS）
└── overlays/
    └── kind/                   本機：base + postgres + llm-chat-external，NodePort 接到 127.0.0.1
        ├── cluster.yaml        kind 叢集：1 control-plane + 2 worker
        └── up.sh               起叢集、放映像檔、建 Secret、部署

ops/k8s/                        觀測的收集端：Alloy DaemonSet（理由見 ops/README.md）
├── base/                       DaemonSet、只讀 Pod 的 RBAC、config.alloy
└── overlays/kind/              Mimir/Loki/Tempo 指到主機上 ops/compose.yaml 那組容器
```

EKS 要做的就是多一個 `overlays/eks/`：`components: [../../components/llm-chat-gpu]`、補 S3 的 URI 和 `gp3`、
資料庫位址換成 RDS、入口換成 ALB。base 一行都不用動。

## 選型：為什麼是 kustomize

| | 純 YAML | **kustomize（選這個）** | Helm |
|---|---|---|---|
| 一份設定給 kind、EKS 兩個環境 | 複製兩份，改一邊忘一邊 | base + overlay，overlay 只寫差異 | 一份 template + 每環境一份 values |
| 讀起來 | 就是 k8s 的 YAML | **還是** k8s 的 YAML，每個檔案單獨看得懂、`kubectl apply --dry-run` 驗得了 | Go template 混在 YAML 裡，要先 render 才知道長怎樣 |
| 要裝什麼 | 不用 | 不用：`kubectl apply -k` 內建 | 要裝 helm |
| 版本紀錄、一鍵回退 | 沒有 | 沒有（交給 git 和 `kubectl rollout undo`） | 有（`helm rollback`） |
| 適合 | 只有一個環境 | **自己的 app、環境之間差一點點** | 要發給別人裝的套件（例如 Grafana 官方的 chart） |

2026 年的主流做法：**自己的 app 用 kustomize，裝第三方的東西用 Helm**，兩者常一起用（Argo CD、Flux 都原生支援 kustomize）。
這個專案只有自己的 app、兩個環境，差別只有「模型在哪、資料庫在哪、入口是什麼」，用 Helm 的 template 是殺雞用牛刀。
回退（[#19](https://github.com/Anderson-chen/talkcal/issues/19)）靠 `kubectl rollout undo` 或改回 overlay 的 image tag，不需要 Helm 的 release 紀錄。
參考：[Kustomize vs Helm 2026](https://lucaberton.com/blog/kustomize-vs-helm-2026/)、[Helm vs Kustomize (2026)](https://devopsboys.com/blog/helm-vs-kustomize-2026)

**為什麼用 component 而不是 overlay 疊 overlay**：「模型在哪」和「資料庫在哪」是兩個獨立的選擇
（kind 是外面的模型 + 叢集裡的資料庫，EKS 是叢集裡的模型 + RDS）。component 讓 overlay 像點菜一樣挑，
疊 overlay 的話兩個選擇會被綁在同一條繼承線上。

## compose → k8s 對照

| compose（deploy/compose.yaml） | k8s（這裡） | 寫在 |
|---|---|---|
| 一個 `app` 容器 | Deployment `replicas: 2` | base/app.yaml |
| `healthcheck`（一種） | `startupProbe` + `livenessProbe` + `readinessProbe`（三種） | base/app.yaml |
| 換版：先停舊的再起新的（有空窗） | `RollingUpdate` `maxSurge: 1` `maxUnavailable: 0`：先起新的、ready 了才關舊的 | base/app.yaml |
| `stop_grace_period: 2m30s` | `terminationGracePeriodSeconds: 150` + `preStop` sleep 10 秒 | base/app.yaml |
| `model-seed` 跑完才起 `llm-chat` | initContainer | components/llm-chat-gpu |
| `models` 具名 volume | PersistentVolumeClaim | components/llm-chat-gpu |
| `deploy.resources...driver: nvidia` | `resources.limits: nvidia.com/gpu: 1` | components/llm-chat-gpu |
| 服務名稱 `app`、`llm-chat`、`postgres` | 同名的 Service | 各檔案 |
| `deploy/.env` 的 `DB_PASSWORD` | Secret `talkcal-db`（up.sh 從同一份 .env 建） | overlays/kind/up.sh |
| `ports: 127.0.0.1:18080:80` | NodePort 30080 → kind 的 `127.0.0.1:38080` | overlays/kind |
| Alloy 掛 `docker.sock` | Alloy DaemonSet 唯讀掛 `/var/log/pods` | ops/k8s |

### 三種探針各回答什麼

| 探針 | 問的問題 | 失敗會怎樣 | app 打哪裡 | 看什麼 |
|---|---|---|---|---|
| startup | 開機完了沒？ | 還沒完之前不問另外兩個；2 分鐘還沒好才重啟 | `/actuator/health/liveness` | JVM 起好了 |
| liveness | 卡死了嗎？ | **重啟**這個容器（只等 10 秒就 SIGKILL，不等 150 秒，見驗證 3） | `/actuator/health/liveness` | 只看 app 自己（不看資料庫：重啟修不好資料庫） |
| readiness | 現在能接請求嗎？ | **不重啟**，從 Service 拿掉，好了自動加回來 | `/actuator/health/readiness` | app 自己 + 資料庫（不看模型：模型掛了行程照樣能用） |

### 換版為什麼不會有空窗

rolling update 只解決一半：新的 Pod ready 了才關舊的，**但關舊的那一刻**還有兩個時間差 ——

1. Pod 被標成要刪除時，「從 Service 拿掉」和「送 SIGTERM」是同時開始的。kube-proxy 要過一下才更新規則，
   這段時間還會有請求被導到舊 Pod。→ `preStop` 先睡 10 秒，等大家都知道它被拿掉了，才送 SIGTERM。
2. SIGTERM 之後 Spring 等手上的請求做完（最多 2 分鐘）。→ `terminationGracePeriodSeconds: 150` 比它長，
   不會等到一半被 SIGKILL（[postmortem](../../docs/postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)）。

模型（`components/llm-chat-gpu`）刻意用 `Recreate`，換模型時有空窗：新舊並存要兩張 GPU。跟 compose 的 `deployModels` 同一個取捨。

## 本機 kind：模型為什麼連主機上的

本機只有一張 12GB 的卡，`deploy/` 的 `llm-chat` 容器已經佔了 11GB。在 kind 裡再起一份放不下，
而且 kind 的節點要另外裝 NVIDIA 的 container toolkit 才看得到 GPU（Docker Desktop 的 WSL2 環境上不穩）。

所以 kind 用 `components/llm-chat-external`：Service `llm-chat` 是 `ExternalName`，指到 compose 的模型容器 `talkcal-llm-chat`。
up.sh 把 kind 的節點（本身也是 Docker 容器）接上 compose 的網路，Pod 問 DNS 就問得到那個容器名稱。

| | 模型在叢集裡（llm-chat-gpu） | 連主機上的（llm-chat-external，kind 用這個） |
|---|---|---|
| 驗得到 | GPU 排程、initContainer、PVC | app 換版、探針、觀測 —— 這次要驗的都在 app 這邊 |
| 驗不到 | — | initContainer 從 S3 拉模型、GPU 節點排程 → 留到 #58 在 EKS 上驗 |
| app 的設定 | 一樣：打 `http://llm-chat:8080` | 一樣 |

## 怎麼跑

```bash
docker compose -f deploy/compose.yaml up -d          # 模型、映像檔都從這組來
docker compose -f ops/compose.yaml up -d             # 要看 log、指標才需要
deploy/k8s/overlays/kind/up.sh
kubectl -n talkcal get pods -o wide
```

前端開 <http://127.0.0.1:38080>，app 在 <http://127.0.0.1:38090/actuator/health>。收掉：`kind delete cluster --name talkcal`。

第一次起的時候 app 會各重啟一次：k8s 沒有 `depends_on`，postgres 還沒好時 Flyway 連不上、app 結束，
kubelet 重啟它，等到資料庫好了就起得來。這是 k8s 的慣例（每個服務自己撐得過依賴還沒好），不另外加 initContainer 等資料庫。

## 驗證（2026-10-16，kind v0.30.0、k8s v1.31.1）

每一項都做了反例：把那個設定拿掉，證明問題真的會出現；放回去，證明它擋得住。
負載用 [perf/rollout.js](../../perf/rollout.js)（`GET /api/auth/providers`，經過 web 的 nginx 再到 app）。

### 1. 換版沒有空窗

`kubectl -n talkcal rollout restart deployment/app deployment/web`，k6 每秒 20 次、打 100 秒：

| 設定 | 成功 | 失敗 | 說明 |
|---|---|---|---|
| **現在的設定**（RollingUpdate + 探針 + preStop） | **2001** | **0** | app、web 同時換 |
| 反例：`strategy: Recreate` | 1634 | 85（502 ×37、逾時 ×48）+ 82 沒送出去 | 舊的全關了才起新的，空窗約 22 秒 |
| 反例：拿掉 readiness、startup 探針 | 1742 | 58（502） | 新 Pod 一起來就被當成 ready，Spring 還沒聽 8090 就被導流量 |
| 反例：拿掉 preStop | 1801 | 0 | 每秒 20 次撞不到 —— 見下一列 |

preStop 擋的那段時間差很短，kind 的節點都在同一台機器上，kube-proxy 幾乎是立刻更新。拉到每秒 150 次、直接打 app（38090）才看得到：

| 設定 | 成功 | 失敗 |
|---|---|---|
| 拿掉 preStop | 10492 | **9**（EOF：Spring 已經不收新連線，kube-proxy 還在導過來） |
| 現在的設定 | 10501 | **0** |

EKS 上這段時間差更長（節點多、ALB 摘掉 target 要好幾秒），preStop 只會更重要。

### 2. readiness 失敗：不收流量、不重啟

`kubectl -n talkcal scale statefulset postgres --replicas=0`，停 90 秒再放回來：

- 5 秒內兩個 app Pod 都從 Service 拿掉（`endpoints` 的 `addresses` 變空、移到 `notReadyAddresses`），`/api` 回 502
- 這段時間在 Pod 裡打 `/actuator/health/liveness` 一直是 `UP`，**重啟次數維持 0** —— 重啟修不好資料庫
- 資料庫回來後約 15 秒，兩個 Pod 自動加回 Service，`/api` 回 200，重啟次數還是 0

### 3. liveness 失敗：卡死會重啟

在節點上對其中一個 app 的 java 送 `kill -STOP`（程序還在、但不回應任何請求，模擬卡死）：

| 設定 | 卡住 → 被 SIGKILL | 說明 |
|---|---|---|
| 一開始的設定 | **3 分鐘**（08:43:35 → 08:46:34） | liveness 30 秒就判定失敗了，但砍它的時候套用 Pod 的 `terminationGracePeriodSeconds: 150`：卡死的程序不理 SIGTERM，只能等滿 150 秒 |
| **加上 liveness 自己的 `terminationGracePeriodSeconds: 10`** | **36 秒**（08:49:55 → 08:50:31），11 秒後重新 ready | 正常換版照樣等 150 秒，判定卡死時只等 10 秒 |

這是驗證時才發現的：沒有反例的話，「liveness 有設」看起來就是對的。
卡住那段時間（readiness 要約 10 秒才把它拿掉），已經被導到它那邊的請求會逾時：每秒 20 次裡有 61 個等滿 10 秒失敗，另一份 app 照常接請求。

### 4. 觀測：Alloy 讀節點上的 log 檔

- Loki 有 `env="kind"` 的 log，依 `service`、`pod` 分開（app ×2、web ×2、postgres），內容是 app 印的 JSON（CRI 前綴已經拆掉）
- Mimir 有 `up{env="kind", job="talkcal-app"}`，兩個 app Pod 各一條，值是 1
- 三台 Alloy 的 `otelcol_receiver_accepted_spans_total` 都在增加，等於 `otelcol_exporter_sent_spans_total`（trace 都轉給 Tempo 了）
- 反例：compose 那台 Alloy 只收 `com.docker.compose.project=talkcal-deploy` 的容器，kind 的節點不在裡面，所以 `env="kind"` 只可能來自叢集裡的 Alloy

### 5. GPU 版的模型（components/llm-chat-gpu）

kind 上沒有 GPU，只驗到排程這一步：套上去以後 API server 接受、Pod 停在 `Pending`，
原因是 `2 Insufficient nvidia.com/gpu`，排程器確實照 GPU 需求挑節點。initContainer 從 S3 拉模型留到 #58 在 EKS 上驗。

### 這次在 kind 上踩到的

- `kind load docker-image` 在 Docker Desktop 開了 containerd 映像檔儲存時會失敗（`ctr: content digest ... not found`）：
  從 Docker Hub 拉的多平台映像檔只有本機平台的層，它卻帶 `--all-platforms` 匯入。up.sh 改成自己 `docker save` 再用 `ctr import --platform linux/amd64` 匯入
- `web/nginx.conf` 原本寫死 Docker 的 DNS `127.0.0.11`，k8s 裡沒有這個位址。改成 template，啟動時從 `/etc/resolv.conf` 填
