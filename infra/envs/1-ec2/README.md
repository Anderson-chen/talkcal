# 1-ec2 — 階段一：單台 EC2 + Docker Compose

狀態：**2026-10-14 部署上線**（`model_backend=bedrock`），從外部經 HTTPS 打 AI 助理、Grafana 看到三種訊號都驗證過。
機器平常關機，展示前開機。追蹤在 [#57](https://github.com/Anderson-chen/talkcal/issues/57)；整體的階段怎麼分見 [infra/README.md](../../README.md)。
第一次部署遇到的三個問題寫在「疑難排解」最前面。

**互動架構圖**：[https://anderson-chen.github.io/talkcal/](https://anderson-chen.github.io/talkcal/)——點任一元件看它由這個資料夾哪個檔案、哪幾行決定，選擇理由、業界做法和查證來源。

一台 EC2，`deploy/` 和 `ops/` 兩組 compose 搬上去，前面加一個 Caddy 處理 HTTPS。
模型在哪裡跑由變數 `model_backend` 決定，其他（VPC、Caddy、DB、觀測）兩種共用：

| `model_backend` | 機器 | 模型 | 現況 |
|---|---|---|---|
| `bedrock`（預設） | m7i-flex.large，Ubuntu 24.04 | app 呼叫 Bedrock 的 Qwen3 32B（OpenAI 相容 API） | 現在就能部署 |
| `gpu` | g6.xlarge，Deep Learning AMI | 跟本機一樣自己跑 llama-server（Qwen3-8B），模型從 S3 拉 | 等 GPU 配額 |

## 為什麼先走 Bedrock

2026-10-13 申請東京區 G 系列配額（新帳號預設 0）被拒，AWS 要新帳號「逐步增加用量」。一般機型的配額已經有 32 vCPU，
Bedrock 也不受 EC2 GPU 配額限制，所以先把模型換成代管的 API：

- **程式不用改**：app 用 Spring AI 的 OpenAI client，本機打 llama-server、雲端打 Bedrock 的 `bedrock-mantle` 端點，
  只換位址、key、模型名稱（`compose.bedrock.yaml` 用環境變數蓋掉）。
  2026-10-13 在東京實測過 mantle 上 Qwen3 32B 的 `json_schema` 輸出（抽行程用）和工具呼叫（AI 助理用）都能動。
- **便宜**：m7i-flex.large 約 0.1 USD/h（符合免費方案資格），g6.xlarge 約 0.8–1 USD/h；展示用的量，token 費用很少。
- **代價**：雲端跟本機跑的不是同一顆模型（32B vs 8B），少了「同一份 compose 原樣搬上去」；
  多了一把要保管的 API key（見 `bedrock.tf`）。

GPU 配額下來後：`terraform apply -var model_backend=gpu`，再照下面「首次部署」的 gpu 步驟傳模型。
之後想拿掉那把 key，改用 Spring AI 的 Bedrock Converse 模組直接吃 EC2 的角色。

## 1. 使用者的請求怎麼走

```mermaid
flowchart LR
    user["使用者"] -->|"https://talkcal.IP.sslip.io<br/>security group 只開 80/443"| caddy["caddy<br/>自動拿憑證"]
    caddy --> web["web<br/>nginx"]
    web -->|"/api/ 轉給 app"| app["app"]
    app -->|"bedrock"| br["Bedrock<br/>Qwen3 32B"]
    app -.->|"gpu"| llm["llm-chat<br/>L4 GPU"]
    app --> pg[("postgres<br/>EBS 上的 volume")]
```

從 web 往後跟本機一樣，差在入口多了 Caddy、模型依 `model_backend` 換地方。

## 2. 程式、模型、密碼怎麼上機器

```mermaid
flowchart LR
    gh["GitHub<br/>公開 repo"] -->|"git fetch 指定版本"| deploy["deploy.sh<br/>在 EC2 上跑"]
    s3m[("S3<br/>GGUF 模型檔")] -.->|"gpu：aws s3 sync"| deploy
    ssm["SSM Parameter Store<br/>.env（DB 密碼、網址、<br/>Google／GitHub 的 secret、<br/>bedrock：API key）"] -->|"get-parameter"| deploy
    deploy -->|"compose up --build"| stack["deploy/ + ops/<br/>兩組容器"]
```

觀測那組跟本機一模一樣。看 Grafana 用 SSM 的 port forwarding 接回本機，不對外開 3000。

## 檔案

| 檔案 | 做什麼 |
|---|---|
| `backend.tf`、`backend.hcl.example` | state 放 bootstrap 建的 S3 bucket；bucket 名稱帶帳號 ID，放在 gitignore 掉的 `backend.hcl` |
| `variables.tf` | `model_backend` 和依它決定預設值的機型、磁碟；Google、GitHub 登入的 client id／secret（`sensitive`） |
| `oauth.auto.tfvars.example` | 複製成 `oauth.auto.tfvars`（不進 git）填 Google、GitHub 的 client id／secret；沒填就只有帳密登入 |
| `alerting.auto.tfvars.example` | 複製成 `alerting.auto.tfvars`（不進 git）填 Grafana 寄告警信的 SMTP 和收件人；沒填就不寄信（[#121](https://github.com/Anderson-chen/talkcal/issues/121)） |
| `outputs.tf` | 網址、IP、instance id、Grafana 通道指令、要在 Google、GitHub 登記的回呼網址（`oauth_callback_urls`） |
| `vpc.tf` | 一個公有子網，不建 NAT；可用區自動挑有這個機型的 |
| `security-groups.tf` | 只開 80/443（含 HTTP/3 的 UDP 443）；22 預設關 |
| `ec2.tf` | AMI（bedrock：Ubuntu 24.04；gpu：Deep Learning Base AMI）、機型、Elastic IP、強制 IMDSv2 |
| `iam.tf` | 機器的角色：SSM 連線、讀 `.env` 參數，gpu 再加讀模型 bucket，別的都碰不到 |
| `bedrock.tf` | （bedrock）只能呼叫模型的專用 IAM user，和它的 Bedrock API key（90 天過期） |
| `s3.tf` | （gpu）模型檔的 bucket |
| `secrets.tf` | 產生 DB 密碼，連同網址（和 Bedrock 的位址、key、模型、Google／GitHub 的 secret）寫成一份 `.env` 存進 SSM |
| `user-data.sh.tftpl` | 第一次開機：裝或驗證 Docker（gpu 再驗 GPU）、補 compose/buildx、clone、第一次部署 |
| `deploy.sh` | 在機器上跑的部署：拉版本 →（gpu）拉模型 → 拉 `.env` → 兩組 compose up → 冒煙測試 |
| `smoke.sh` | 部署的冒煙測試，`deploy.sh` 呼叫兩次（[#225](https://github.com/Anderson-chen/talkcal/issues/225)）：起容器之前查 `.env` 沒有 `\r`（必要的 key 由 `deploy.sh` 用 `docker compose config -q` 查）；全部起來之後查 https 的網站和 `/api`、Grafana 資料源、Alloy 抓得到 app、（bedrock 時）用 `.env` 的 key 問 Bedrock 一句、（有設 SMTP 時）寄一封測試信。任一項沒過，部署就失敗並印出是哪一項，不印值 |
| `compose.ec2.yaml` | 疊在 `deploy/compose.yaml` 上、兩種共用的雲端差異：DB 密碼、app 記憶體上限、Caddy |
| `compose.bedrock.yaml` | 不起 `llm-chat`、`model-seed`；app 的模型位址、key、名稱換成 Bedrock 的 |
| `compose.gpu.yaml` | 模型來源換成從 S3 拉下來的資料夾 |
| `Caddyfile` | HTTPS 入口，全部轉給 web |
| `compose.ops.yaml` | 疊在 `ops/compose.yaml` 上：Alloy 的 trace 取樣改成一般請求 10%、等 125 秒才決定 |

## 跟本機比，改了什麼

| 項目 | 本機 | EC2 上 | 在哪裡改 |
|---|---|---|---|
| 模型 | 本機 GPU 跑 llama-server（Qwen3-8B） | bedrock：Bedrock 的 Qwen3 32B；gpu：g6.xlarge 的 L4 跑同一份 llama-server（也是 Ada，sm_89，映像檔編的是 `CUDA_ARCH=89`；g4dn 的 T4 是 sm_75，要重編） | `variables.tf`、`compose.bedrock.yaml` |
| 驅動 | Docker Desktop 處理 | gpu：Deep Learning Base AMI 裝好了，開機時印 `nvidia-smi`，CUDA 13 要驅動 580 以上 | `ec2.tf`、`user-data.sh.tftpl` |
| 模型檔 | `deploy/.env` 的 `MODELS_DIR`（`deploy/models/download.sh` 下載） | gpu：S3 → `/opt/talkcal/models`（deploy.sh 設成 `MODELS_DIR`）→ model-seed 照舊驗 SHA256、複製進 volume；bedrock：沒有 | `s3.tf`、`deploy.sh` |
| HTTPS | 沒有 | Caddy + sslip.io：用 IP 推出網域，不必買網域也有 Let's Encrypt 憑證 | `compose.ec2.yaml`、`Caddyfile` |
| 對外的埠 | 全部綁 `127.0.0.1` | 只有 Caddy 開 80/443；Grafana、Loki 原本沒綁位址，已改成 `127.0.0.1`（本機也一起改了） | `ops/compose.yaml`、`security-groups.tf` |
| 登入機器 | 本機 | SSM Session Manager，不開 22 | `iam.tf` |
| DB 密碼 | 自己填在不進 git 的 `deploy/.env`（[#123](https://github.com/Anderson-chen/talkcal/issues/123)） | Terraform 隨機產生 → SSM → 同一個位置的 `deploy/.env`（[#14](https://github.com/Anderson-chen/talkcal/issues/14)） | `secrets.tf`、`compose.ec2.yaml` |
| Google、GitHub 登入 | client id／secret 填在 `deploy/.env`，回呼網址 `http://localhost:5173/...` | `oauth.auto.tfvars` → SSM → `deploy/.env`；回呼網址 `https://<網站>/...`，nginx 照轉 Caddy 的 `X-Forwarded-Proto`、app 信任它才組得出 https（[#127](https://github.com/Anderson-chen/talkcal/issues/127)） | `variables.tf`、`secrets.tf`、`web/nginx.conf` |
| 告警寄信 | SMTP、收件人填在 `deploy/.env`，起 `ops/` 時帶 `--env-file ../deploy/.env`；試寄用 Mailpit（`--profile mailtest`） | `alerting.auto.tfvars` → SSM → `deploy/.env`，`deploy.sh` 起 `ops/` 時帶 `--env-file deploy/.env`（[#121](https://github.com/Anderson-chen/talkcal/issues/121)） | `variables.tf`、`secrets.tf`、`deploy.sh`、`ops/compose.yaml` |
| app 記憶體 | 沒上限 | `mem_limit: 2g`：沒設的話 JVM 的 75% 是算整台（8GB 或 16GB） | `compose.ec2.yaml` |
| Trace 取樣 | 全留 | 錯的、5xx、超過 5 秒的全留，其餘留 10%（[#37](https://github.com/Anderson-chen/talkcal/issues/37)，理由見 [ops/README.md](../../../ops/README.md)） | `compose.ops.yaml` |
| 映像檔 | 本機 build | 在 EC2 上 build；做[版本 tag](https://github.com/Anderson-chen/talkcal/issues/25) 時再改成 CI 推到 ECR | `deploy.sh` |

## 首次部署

前提：AWS CLI 的 `DEV` profile 登入得了。`gpu` 還要 **GPU 配額已經申請下來**（「Running On-Demand G and VT instances」預設是 0，2026-10-13 申請被拒；g6.xlarge 要 4 vCPU）。

在 Git Bash 裡跑 AWS CLI 時，參數裡以 `/` 開頭的字串（例如 SSM 參數名稱 `/aws/service/...`）會被轉成 Windows 路徑，回 `ParameterNotFound`。前面加 `MSYS_NO_PATHCONV=1` 就好。

```bash
# 0. 共用的 state bucket 和花費告警（整個帳號只跑一次）
cd infra/bootstrap
terraform init
terraform apply -var="alert_email=<你的信箱>"

# 1. 基礎設施（預設 bedrock；GPU 配額下來後加 -var model_backend=gpu）
cd ../envs/1-ec2
cp backend.hcl.example backend.hcl        # 填入上一步輸出的 state_bucket
terraform init -backend-config=backend.hcl
# （要開 Google、GitHub 登入才做）cp oauth.auto.tfvars.example oauth.auto.tfvars 再填值，
#  先 apply 一次拿到網址，再到 Google、GitHub 登記 terraform output oauth_callback_urls 印出來的回呼網址
# （要收告警信才做）cp alerting.auto.tfvars.example alerting.auto.tfvars 再填 SMTP 和收件人
terraform apply

# 2.（只有 gpu）上傳模型檔（只要一次；SHA256SUMS 一起傳，model-seed 會驗）
# $MODELS_DIR 是本機 deploy/.env 裡那個資料夾；還沒有模型就先 bash deploy/models/download.sh（會一起寫 SHA256SUMS）
aws s3 cp "$MODELS_DIR/Qwen3-8B-Q4_K_M.gguf" "s3://$(terraform output -raw models_bucket)/" --profile DEV
aws s3 cp "$MODELS_DIR/SHA256SUMS"            "s3://$(terraform output -raw models_bucket)/" --profile DEV
```

機器開機後 user_data 會自動跑第一次部署。gpu 時如果模型檔是在機器開機**之後**才傳上去，第一次部署會停在「S3 裡沒有 .gguf」，傳好之後手動重跑一次：

```bash
aws ssm start-session --target "$(terraform output -raw instance_id)" --region ap-northeast-1 --profile DEV
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

第一次要裝 Docker、build app、拉映像檔，會跑一陣子（gpu 還要編 llama-server 的 CUDA，十幾分鐘），進度看 `sudo tail -f /var/log/user-data.log`。
好了之後開 `terraform output -raw app_url`。

## 日常操作

```bash
# 部署新版：master 合併之後，到機器上重跑
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
# 先部署某個分支試試
sudo GIT_REF=57-ec2 bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
# 有設 SMTP 時每次部署都會寄一封主旨含 DeploySmokeTest 的測試信；同一天要連續部署好幾次就關掉
sudo SMOKE_SEND_EMAIL=0 bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
# 不部署、只重跑部署後的冒煙測試
cd /opt/talkcal/repo && sudo bash infra/envs/1-ec2/smoke.sh services deploy/.env

# 看狀態
cd /opt/talkcal/repo
sudo docker compose -f deploy/compose.yaml -f infra/envs/1-ec2/compose.ec2.yaml -f infra/envs/1-ec2/compose.bedrock.yaml ps
sudo docker stats --no-stream && free -h       # gpu 再加 nvidia-smi

# Bedrock API key 到期前換新（預設 90 天），再到機器上重跑 deploy.sh
terraform apply -replace='aws_iam_service_specific_credential.bedrock[0]'
# 換 DB 密碼（例如懷疑外洩），再到機器上重跑 deploy.sh：它會把資料庫裡的密碼同步成新的那組
terraform apply -replace=random_password.db
# 換 Google、GitHub 的 secret、告警信的 SMTP（或第一次填）：改 oauth.auto.tfvars／alerting.auto.tfvars，apply 之後再到機器上重跑 deploy.sh
terraform apply
# Google、GitHub 要登記的回呼網址（Elastic IP 換了就會跟著換，要重新登記）
terraform output oauth_callback_urls

# 資料庫備份（自動：每小時、關機前、部署前）。看狀態、手動補一份；還原步驟見 docs/runbooks/backup-restore.md
systemctl list-timers talkcal-backup.timer && cat /opt/talkcal/backups/last-success
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/backup-to-s3.sh manual

# 看 Grafana：在本機跑這行，再開 http://localhost:3000
terraform output -raw grafana_tunnel
```

## 省錢

機器按小時計費（bedrock 的 m7i-flex.large 約 0.1 USD/h，gpu 的 g6.xlarge 約 0.8–1 USD/h），平常關機、展示時才開。
關機時還在收費的只有 EBS（bedrock 30GB、gpu 150GB 的 gp3）和沒掛在執行中機器上的 Elastic IP。Bedrock 按 token 計費，不叫就不收。

```bash
export HEALTHCHECKS_API_KEY=...   # 有設 heartbeat_url 才需要；#40 做完之前不要設，見下面
./power.sh stop                   # 先暫停 Healthchecks 的 check，再關機
./power.sh start                  # 開機，等到 Healthchecks 收到 heartbeat、恢復監控
```

為什麼不直接 `aws ec2 stop-instances`：關機後 Grafana 的 heartbeat 也停了（#40），不先暫停的話 Healthchecks 約 6 分鐘後
會寄一封「掛了」的信，關一次響一次。`power.sh stop` 先用 Healthchecks 的 API 暫停，再關機；
暫停中的 check 收到 Grafana 的第一個 heartbeat 就自己回到監控（本機實測過）。但 Grafana 開機後沒起來的話，
它會一直停在暫停、不會寄信，所以 `power.sh start` 開機後會等到 check 回到 up；10 分鐘還沒恢復就報錯，要自己連進去看。

**現在不要設 `heartbeat_url`**：Watchdog 先拿掉了（#243），沒有東西會打 heartbeat，設了的話 `power.sh start` 會等滿 10 分鐘報錯、
Healthchecks 也會寄 DOWN 的信。#40 做完再設。已經設過的，部署 #243 之前先到 Healthchecks.io 把 check 按 Pause。

`HEALTHCHECKS_API_KEY` 在 Healthchecks.io 的 Project Settings → API Access 產生，要能寫的那種（read-only 不能暫停）。
只有這支腳本用得到、機器上不需要，所以不放 SSM、不進 git，用環境變數給。沒給的話照樣關機，只是會收到那封信。

容器都設了 `restart: unless-stopped`，開機後會自己起來，不必重跑 deploy.sh；gpu 時模型要重新載入，第一個請求會等比較久。
關機前 systemd 會先把資料庫備份到 S3（`talkcal-backup-on-shutdown`），所以關機這段時間的 RPO 是 0；關機要等它跑完（幾秒）。

徹底收攤用 `terraform destroy`：IP 釋出、網址跟著換，機器上的資料庫也一起消失。
備份 bucket 有東西時 destroy 會停在那裡（`force_destroy = false`，刻意的）：先決定要不要把備份下載回來，再清空 bucket 重跑。

## 代價

- 只有一台：機器掛了服務就停，部署時那十幾秒沒人接也還在。
- rolling update、canary 在單機上做不太出來，這些留給[階段二](../2-eks/README.md)。
- 機器上 build 映像檔：第一次很慢，而且線上跑的映像檔沒有版本號，退版要重新 build 舊的 commit。

## 已經決定的

| 問題 | 選擇 | 理由 |
|---|---|---|
| 模型在哪跑 | 先走 Bedrock，`model_backend` 留著切回 gpu | GPU 配額被拒；理由見上面「為什麼先走 Bedrock」 |
| 機型（bedrock） | m7i-flex.large | 8GB 放得下 app、Postgres、Caddy、整組觀測；t3.small（2GB）、c7i-flex.large（4GB）不夠 |
| 機型（gpu） | g6.xlarge | 映像檔是 sm_89，L4 不用重編；T4 便宜但要另外編一份 |
| Bedrock 怎麼驗證身分 | 長期 API key（專用 IAM user，90 天過期），存 SSM | app 的 OpenAI client 只會送 Bearer key、不會 SigV4；先用 key 驗證模型合不合用，之後再換成吃 EC2 角色的 Bedrock Converse |
| HTTPS | Caddy + sslip.io | 一個設定檔包辦憑證和續期，不必買網域；跟 cool-down 同一套做法 |
| 觀測放哪 | 同一台，跟本機同一份 `ops/` | 最能證明「搬得動」；記憶體不夠再考慮 Grafana Cloud |
| 映像檔從哪來 | 機器上 build | 不必先從家裡推幾 GB 的 CUDA 映像檔上 ECR；版本 tag 做了再改 |
| 資料庫備份 | 每小時、關機前、部署前 `pg_dump` 到 S3（`backups.tf`），30 天；不做 EBS 自動快照 | 機器能從程式碼重建，要保護的只有資料庫；pg_dump 能只救一張表、跨版本還原。RPO：正常關機 0、當機 ≤ 1 小時。理由和還原步驟見 [runbook](../../../docs/runbooks/backup-restore.md)（[#45](https://github.com/Anderson-chen/talkcal/issues/45)） |
| Region | 東京 ap-northeast-1 | 2026-10-13 查過：台北 ap-east-2 沒有任何 G 系列機型（g4dn、g5、g6 都沒有）；東京的 g6.xlarge 在 1a、1c 兩個可用區 |

## 待決定

| 問題 | 選項 | 目前傾向 |
|---|---|---|
| 開機策略 | 常駐 / 平常關機、展示才開 | 平常關機 |

## 疑難排解

**第一次部署（2026-10-14）真的遇到的**，都已經修掉；重建一台全新的機器驗證過，開機到部署完約 4 分鐘：

| 症狀 | 原因 | 修正 |
|---|---|---|
| bedrock 模式卻在編 llama-server 的 CUDA，2 vCPU 編 25 分鐘才 45% | `deploy.sh` 第 4 步沒疊 `compose.bedrock.yaml`（改它的那次替換沒套上，只驗了 `bash -n`） | [#74](https://github.com/Anderson-chen/talkcal/issues/74)：改成 `compose_files` 陣列；用 `--dry-run` 確認只建 app、caddy、postgres、web |
| 第 4 步報「缺 DB_PASSWORD」 | snap 版的 aws 輸出導向檔案時結束碼 0、寫出 0 bytes（導向管線才正常），`deploy/.env` 是空的 | [#74](https://github.com/Anderson-chen/talkcal/issues/74)：改用 AWS 官方安裝程式；`deploy.sh` 寫完 `.env` 先檢查有 `DB_PASSWORD` |
| AI 助理回 502：`The model 'gpt-5-mini' does not exist` | Spring AI 2.0.1 看到請求自帶選項就整份照用、不補設定檔的預設，模型名稱從沒生效；本機 llama-server 不看名稱所以一直沒事 | [#76](https://github.com/Anderson-chen/talkcal/issues/76)：請求的選項從 `chatModel.getOptions()` 開始再蓋 |
| 部署成功、首頁 200，但 `/api` 全部 502；nginx log 是 `connect() failed` 連到舊 IP | 這次只重建了 app、web 沒重建：nginx 只在啟動時把 `app` 解析一次，app 換了 IP 它還連舊的 | [#90](https://github.com/Anderson-chen/talkcal/issues/90)：`web/nginx.conf` 改成執行期問 Docker DNS（`resolver 127.0.0.11 valid=10s`）；本機用「佔掉舊 IP、重建 app」重現，舊版 502、新版 200 |
| （2026-10-29）從 Windows apply 之後部署，app 一直重啟：`password authentication failed for user "talkcal"` | 這台的 `core.autocrlf=true`，`.tf` 取出是 CRLF，`secrets.tf` 的 heredoc 連 `\r` 寫進 SSM 的 `.env`；`deploy.sh` 用 grep 取出的 DB 密碼多一個 `\r`，`ALTER ROLE` 設成它，compose 交給 app 的卻沒有 | [#121](https://github.com/Anderson-chen/talkcal/issues/121)：`.gitattributes` 把 `*.tf` 釘成 LF；SSM 參數裡 `\r` 從 15 個變 0 個，重新 apply、部署後 app healthy。[#225](https://github.com/Anderson-chen/talkcal/issues/225)：`smoke.sh env` 在起容器之前就擋下、指出是 `.env` 的換行 |

其他預期可能遇到的：

| 症狀 | 檢查 |
|---|---|
| `terraform apply` 開不出機器，說 vCPU 上限 | gpu：GPU 配額還沒申請或還沒核准（Service Quotas → EC2 → Running On-Demand G and VT instances） |
| AI 助理回 502、app log 說 401／403；或部署第 6 步 `✗ Bedrock：HTTP 401` | Bedrock API key：過期了（`terraform output` 看不到，到 IAM → Users → talkcal-bedrock 看）、或 `.env` 是舊的（apply 後要重跑 deploy.sh） |
| app log 說 429 | Bedrock 的 token 配額：新帳號預設比較低，Service Quotas → Amazon Bedrock 查 Qwen3 32B 的 tokens per minute |
| Alloy 一直報抓不到 `llm-chat:8080` | bedrock 時沒有 llm-chat，`ops/` 那份抓模型指標的設定落空，無害 |
| user_data 沒跑完 | `sudo cat /var/log/user-data.log`。bedrock：看 Docker 的 apt 安裝那段；gpu：`nvidia-smi` 失敗是 AMI 驅動的問題；`docker compose` 失敗看 compose / buildx 有沒有裝上 |
| llm-chat 起不來，log 說 CUDA 版本 | 驅動太舊，CUDA 13 要 580 以上：`nvidia-smi` 右上角的 CUDA Version |
| 憑證拿不到 | security group 的 80 要對 0.0.0.0/0 開（Let's Encrypt 的 HTTP-01 驗證走這裡）；`docker logs talkcal-caddy` |
| 容器被 OOM kill | `docker inspect <名稱> --format '{{.State.OOMKilled}}'`；先調 `mem_limit`，再考慮換 g6.2xlarge |
