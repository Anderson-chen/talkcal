# cd —— 持續部署（CD）

把某個版本放上某台機器、等它 healthy。本機和 AWS 跑的是**同一支 script**，差別只在設定和誰來觸發。
跟 [ci/](../ci/README.md) 同一個分法：做什麼只寫一次，平台和環境只是外面那一層。

## 怎麼跑

在 repo 根目錄（Git Bash）：

```bash
cd/apply.sh local                        # 部署 app、web（不含模型）
cd/apply.sh local web                    # 只部署前端
cd/apply.sh local llm-chat               # 只換模型
WAIT_TIMEOUT=600 cd/apply.sh local app   # 拉長等待
```

平常用 Gradle 的快捷鍵就好（在 `backend/`）：`./gradlew deploy`、`deployApp`、`deployWeb`、`deployModels`，
它們呼叫的就是上面這支，只多了 `deployApp` 先跑測試。用法見 [deploy/README.md](../deploy/README.md)。

## 三件事分開

| 問題 | 放哪 | 本機 | AWS（EC2） |
|---|---|---|---|
| **做什麼**：換服務、等 healthy | `apply.sh` | 同一支 | 同一支 |
| **在哪做**：compose 疊哪幾份、模型和 .env 從哪來 | `envs/<env>.env` | `local.env`：只有 `deploy/compose.yaml` | `ec2.env`（還沒搬過來） |
| **誰觸發、過哪道關** | 觸發的那一方 | 手動：`./gradlew deploy`，先跑測試 | 之後：CI 綠燈 |

本機和 EC2 其實都是「一台有 docker compose 的機器」，所以在機器上跑的東西可以一樣；
不一樣的只有設定，以及觸發怎麼送到機器上（本機直接跑、EC2 透過 SSM）。

`apply.sh` 不跑測試：要過哪道關是觸發方的事。手動部署由 Gradle 先跑測試，之後的 CD 等 CI 綠燈，
不必在部署這一步再檢查一次。

### 跟 infra/ 的分界

- [infra/](../infra/README.md)：**把機器生出來**（VPC、EC2、權限）。很少跑，`terraform apply`。
- `cd/`：**把某個版本放上機器**。每次改動都跑。

## 為什麼每個服務分開換

`apply.sh` 一次換一個服務，每個都帶 `--no-deps`：

- **不帶 `--no-deps` 會順便重啟模型**：compose 會沿著 `depends_on` 走到 `model-seed`。它是跑完就結束的一次性容器，
  compose 每次都重建它，`llm-chat` 依賴它就跟著被重建 —— 部署 app 等於重啟模型
  （2026-10-08 改名後第一次部署時從 compose 的輸出發現，`docker compose up --dry-run app` 可以重現）。
  web 也一樣：不帶的話 `--build` 會沿著 `depends_on` 把沒跑過測試的 app 一起重 build、換掉。
- **代價**：`postgres` 和 `llm-chat` 要先起好。第一次部署前先 `docker compose -f deploy/compose.yaml up -d postgres llm-chat`；
  沒起的話 app 連不上資料庫，`--wait` 會等到逾時報錯，不會假裝成功。
- **模型不在預設裡**：映像檔重 build 約十分鐘，換的期間所有請求都會失敗。改程式碼不該順便付這個代價。

## 還沒做的

- `verify.sh`：部署後打一次 health 和 `/api/calendar`，不只看容器的 healthcheck
- 本機 CD：pull 型的 watcher，master 的 CI 綠燈就自己拉下來部署（GitHub 不用連進這台電腦）
- `envs/ec2.env`：把 [infra/envs/1-ec2/deploy.sh](../infra/envs/1-ec2/deploy.sh) 收進來，EC2 也跑 `apply.sh`
- 現在部署的是「目前 checkout 的版本」、在機器上 `--build`，還不是 build once；等[版本 tag](../docs/production-readiness/release.md#release-tag) 有了，輸入改成映像檔 tag
