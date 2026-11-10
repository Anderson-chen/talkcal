# cd：持續部署（CD）

把某個版本放上某台機器，等到它 healthy。本機和 AWS 跑同一支 script，差在設定和誰來觸發。分法跟 [ci/](../ci/README.md) 一樣：要做的事只寫一次，平台和環境是外面那一層。

整條 CI → CD 的流程圖和每一關擋什麼，見 [docs/architecture.md](../docs/architecture.md#cicd從-commit-到線上)。每支腳本（CI、CD，以及備份、EC2）什麼時候跑、誰叫它、對應哪個 `just` 指令，見[腳本地圖](../docs/automation.md)。

## 使用方式

在 repo 根目錄（Git Bash）：

```bash
cd/apply.sh local                        # 部署 app、web（不含模型）
cd/apply.sh local web                    # 只部署前端
cd/apply.sh local llm-chat               # 只換模型
WAIT_TIMEOUT=600 cd/apply.sh local app   # 拉長等待
```

映像檔的 tag 是 `cd/version.sh` 算出來的版本號：有 release tag 就用 tag，沒有就用 commit 前 7 碼。部署完用 `docker inspect talkcal-app` 看 label 就知道是哪一版。規則和怎麼打 release tag（`cd/release.sh`）見 [docs/release.md](../docs/release.md)。

平常在 `backend/` 用 Gradle 的捷徑就好：`./gradlew deploy`、`deployApp`、`deployWeb`、`deployModels`。它們呼叫的就是上面這支，只多了 `deployApp` 會先跑測試。用法見 [deploy/README.md](../deploy/README.md)。

## 三件事分開

| 問題 | 放哪 | 本機 | AWS（EC2） |
|---|---|---|---|
| 做什麼：換服務、等 healthy | `apply.sh` | 同一支 | 同一支 |
| 在哪做：compose 疊哪幾份、模型和 .env 從哪來 | `envs/<env>.env` | `local.env`：只有 `deploy/compose.yaml` | `ec2.env`（還沒搬過來） |
| 誰觸發、過哪道關 | `triggers/`、Gradle | 手動：`./gradlew deploy`，先跑測試<br>自動：`triggers/watch.sh local`，等 CI 綠燈 | 之後：同一支 watcher，`ec2` |

本機和 EC2 都是一台有 docker compose 的機器，所以在機器上跑的東西可以一樣。不一樣的只有設定，和觸發怎麼送到機器上：本機直接跑，EC2 透過 SSM。

`apply.sh` 不跑測試，要過哪道關由觸發的一方決定。手動部署時 Gradle 先跑測試，自動部署時等 CI 綠燈，部署這一步不用再檢查一次。

### 跟 infra/ 的分界

[infra/](../infra/README.md) 負責把機器生出來（VPC、EC2、權限），很少跑，用 `terraform apply`。`cd/` 負責把某個版本放上機器，每次改動都跑。

## 為什麼每個服務分開換

`apply.sh` 一次換一個服務，每個都帶 `--no-deps`。

不帶 `--no-deps` 的話，compose 會沿著 `depends_on` 走到 `model-seed`。它是跑完就結束的一次性容器，compose 每次都會重建它，依賴它的 `llm-chat` 也跟著重建，結果部署 app 等於重啟模型。這是 2026-10-08 改名後第一次部署時，從 compose 的輸出發現的，用 `docker compose up --dry-run app` 可以重現。web 也一樣：不帶的話 `--build` 會沿著 `depends_on` 把沒跑過測試的 app 一起重 build、換掉。

代價是 `postgres` 和 `llm-chat` 要先起好。第一次部署前先跑 `docker compose -f deploy/compose.yaml up -d postgres llm-chat`。沒起的話 app 連不上資料庫，`--wait` 會等到逾時報錯，不會假裝成功。

模型不在預設裡：映像檔重 build 要十分鐘左右，換的期間所有請求都會失敗，改程式碼不該順便付這個代價。

## 本機 CD：master 綠燈就自動部署

```
master CI ✅ ──（GitHub 不做任何事）
                        ▲
                        │ 每 5 分鐘：最新一個綠燈的 commit 是哪個？
             .git/cd-watch.sh local（部署用的 clone 裡 watcher 的副本，工作排程觸發）
                        │ 沒試過 → checkout → 那個 commit 的 cd/apply.sh local
                        ▼
             結果寫回 GitHub Deployments（repo 頁面右側的 Deployments → local）
```

### 第一次設定

部署用的 clone 要跟開發用的分開。watcher 會 checkout 別的 commit，如果跑在開發的那份，會把你的分支換掉；有還沒 commit 的改動時它會拒絕，但不要靠這個。路徑要短：Windows 預設路徑上限 260 字，整合測試的檔名很長，放在太深的資料夾 checkout 會失敗。

```powershell
git clone https://github.com/Anderson-chen/talkcal.git C:/talkcal-deploy
cd C:/talkcal-deploy
.\cd\triggers\install-local.ps1           # 登記工作排程 talkcal-cd-local，每 5 分鐘一輪
```

`install-local.ps1` 會把 watcher 複製一份到 `.git/cd-watch.sh`，排程跑的是這份。原因是 `cd/triggers/watch.sh` 會跟著 checkout 變，部署到還沒有 watcher 的舊版本時它就不見了，第一次設定時真的發生過（#68）。副本在部署成功後才換成新版。

Windows 預設不讓 `.ps1` 執行，被擋的話改用 `powershell -ExecutionPolicy Bypass -File .\cd\triggers\install-local.ps1`。它只在登記時跑一次，之後每 5 分鐘被叫起來的是 `watch.sh`。

事前要有三樣：`gh auth login`（watcher 用它查 CI、寫部署紀錄）、Docker Desktop 開著、`postgres` 和 `llm-chat` 已經起好（理由見[為什麼每個服務分開換](#為什麼每個服務分開換)）。

### 日常

| 想做的事 | 怎麼做 |
|---|---|
| 看部署了什麼 | GitHub repo 頁面的 Deployments，或 `C:/talkcal-deploy/.git/cd-local.log` |
| 立刻跑一輪，不等排程 | `/c/talkcal-deploy/.git/cd-watch.sh local`（Git Bash），或 `Start-ScheduledTask talkcal-cd-local` |
| 失敗的版本修好了要重試 | 刪掉 `.git/cd-local.last` 再跑一輪。同一個 commit 只自動試一次：失敗要人來看，不該每 5 分鐘重 build |
| 新版壞了，退回上一版 | `cd/rollback.sh local --previous`（或指定版本；`--list` 看有哪些）。會順便釘住，watcher 不再自動部署，修好後 `--unpin`。見 [docs/rollback.md](../docs/rollback.md) |
| 暫停 | `Disable-ScheduledTask talkcal-cd-local`（恢復：`Enable-`；整個拿掉：`Unregister-`） |
| watcher 本身有 bug（log 不再有新的一輪） | 副本會跟著部署換成新版，壞掉的 watcher 部署不到修好的版本，自己救不回來。修好、合併後手動放回：`cp cd/triggers/watch.sh .git/cd-watch.sh`（#70 就是這樣救回來的） |
| 關機、重開機 | 不用管。排程登記在 Windows 裡，登入後自己恢復，關機時錯過的那輪會補跑。Docker Desktop 還沒起來的那幾輪會跳過、不記成試過 |

開發用的 clone 照樣可以 `./gradlew deploy`。兩邊換的是同一組容器（compose 專案都叫 `talkcal-deploy`），手動部署的版本會留到下一個綠燈的 commit 出現為止。

### 為什麼是 pull

| | Push：GitHub Actions → OIDC → SSM 連進機器 | Pull：機器自己去看（選這個） |
|---|---|---|
| 外部能不能對機器下指令 | 能，靠 IAM、自訂 SSM Document 收窄到「只能部署某個 sha」 | 不能，沒有進來的通道 |
| GitHub 要拿到什麼權限 | AWS 的 role | 不用。反過來是機器拿 GitHub 的 token（讀 CI、寫部署紀錄） |
| 本機和 AWS 一致 | 本機做不到（不該讓 GitHub 連進自己的電腦），兩套流程 | 同一支 watcher，只差 env |
| 延遲 | 合併完馬上部署 | 最多一個輪詢間隔 |
| 部署結果在哪看 | Actions 頁面 | 回寫 Deployments 補回來；詳細 log 在機器上 |
| watcher 自己掛掉 | Actions 會紅 | **沒人知道**，要另外有「多久沒部署了」的告警（還沒做） |
| 之後 | — | 跟 EKS 上的 ArgoCD 是同一個模型：叢集自己拉期望的狀態 |

不自架 Jenkins。CI 已經在 GitHub Actions，多一台 Jenkins 只是多一台要更新、修補、備份的伺服器。真的要換平台，`ci/run.sh`、`cd/apply.sh` 都不用改，讓新平台的設定檔呼叫它們就好。

## 還沒做的

- `verify.sh`：部署後打一次 health 和 `/api`，不只看容器的 healthcheck。EC2 上已經有 [infra/envs/1-ec2/smoke.sh](../infra/envs/1-ec2/smoke.sh)（[#225](https://github.com/Anderson-chen/talkcal/issues/225)），收進 `apply.sh` 時直接搬過來。
- `envs/ec2.env`：把 [infra/envs/1-ec2/deploy.sh](../infra/envs/1-ec2/deploy.sh) 收進來，讓 EC2 也跑 `apply.sh` 和同一支 watcher。
- build once：現在部署的是 checkout 下來的程式碼，在機器上 `--build`。映像檔已經帶版本號（[docs/release.md](../docs/release.md)），但沒有推到 registry，本機和 EC2 各自 build。換回機器上舊 tag 的做法見 [docs/rollback.md](../docs/rollback.md)。

## 已知限制，決定不做

「多久沒部署了」的告警。pull 型最大的盲點是 watcher 自己安靜地掛掉，#68、#70 都是這樣，靠看 log 才發現。這是一個人的專案，本機部署壞了自己會看到，所以 2026-10-14 決定先不做。
