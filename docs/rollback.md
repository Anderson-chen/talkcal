# Rollback：退回上一個版本（#19）

新版本上線後壞了，先把服務換回之前好的版本，再慢慢修。

```bash
cd/rollback.sh local --list          # 機器上有哪些版本退得回去、現在跑哪個、有沒有釘住
cd/rollback.sh local --previous      # 退到 GitHub Deployments 上一個成功部署的版本
cd/rollback.sh local 3f9c2ab         # 退到指定版本（版本號 = 映像檔 tag）
cd/rollback.sh local --unpin         # 修好了：解除釘住，watcher 下一輪照常部署
```

在**部署用的 clone**（`C:/talkcal-deploy`）跑，跟 watcher 同一份：釘住和鎖都放在那個 clone 的 `.git` 裡（見[釘住](#退完之後watcher-不會把新版部署回來)）。

**依賴 [#25](https://github.com/Anderson-chen/talkcal/issues/25)（版本與 release tag）**：退版是「換回某個 tag 的映像檔」，
要先有每個版本一個 tag（`talkcal/app:<版本號>`、label 帶 commit）。#25 合併前的映像檔都叫 `:local`，
所以能退的範圍從 #25 合併後的第一次部署開始。版本號的規則見 [docs/release.md](release.md)。

## Runbook

### 1. 先決定：退，還是往前修

| 情況 | 做法 | 理由 |
|---|---|---|
| 剛部署完就壞了，上一版是好的，兩版之間**沒有** migration | **退**（`--previous`） | 最快止血：不用 build、不用等 CI，幾十秒 |
| 兩版之間有 migration，但照 expand/contract 寫（上一版讀寫得了新 schema） | **退** | 這就是 expand/contract 要保證的事（見[資料庫](#資料庫schema-不退)） |
| 兩版之間有**不相容**的 migration（例如 V11：加了 NOT NULL 欄位、沒有預設值） | **往前修**：hotfix（[#26](https://github.com/Anderson-chen/talkcal/issues/26)，PR #131 的 `docs/hotfix.md`） | 退回去容器會 healthy，但寫入全部失敗（下面的驗證 N 真的發生了） |
| 兩版之間有**安全、權限**的修正 | **往前修** | 退回去等於把漏洞放回來。驗證 N：退到 #104 之前，新註冊的人讀得到別人的行程 |
| 只有一小塊功能壞了，其他正常 | 看影響，多半 hotfix | 退版會把同一版其他的修正也一起退掉 |

看兩版之間有沒有 migration：

```bash
git diff --stat <舊版 commit> <新版 commit> -- backend/src/main/resources/db/migration
```

### 2. 確認現在跑的是哪版、能退到哪版

```bash
cd/rollback.sh local --list
curl -s 127.0.0.1:18090/actuator/info      # app 自己回報的版本和 commit（#25）
```

GitHub repo 頁面右側的 Deployments → `local` 也看得到每次部署的 commit 和成敗。

### 3. 退

```bash
cd/rollback.sh local --previous   # 或指定版本
```

它會依序做：

1. 檢查版本號格式、`talkcal/app:<版本>` 和 `talkcal/web:<版本>` **兩個都在** —— 不在就結束，**什麼都沒動**
2. 拿 watcher 的鎖（`.git/cd-local.lock`）：watcher 正在部署就不退
3. 寫下釘住（`.git/cd-local.pin`）
4. 先換 web、再換 app，每個都 `--no-build --pull never --no-deps --wait`，換完確認容器跑的真的是那個 tag
5. 任何一個起不來 → **換回原本在跑的版本**、拿掉這次的釘住，結束碼 1
6. 成功 → 記進 `.git/cd-local.history`，並在 GitHub Deployments 記一筆（連不上 GitHub 不算失敗）

### 4. 確認

```bash
curl -s 127.0.0.1:18090/actuator/info
docker compose -f deploy/compose.yaml logs --since 5m app | grep -i error
```

容器 healthy 不代表功能正常（`compose up --wait` 擋不住的，見 [docs/architecture.md](architecture.md#每一關擋什麼擋不住什麼)）：
登入、看月曆、新增一筆行程都走一次。

### 5. 修好之後

修正合併、master CI 綠燈 → `cd/rollback.sh local --unpin` → watcher 下一輪部署最新的綠燈版本。
剛才壞掉的那個 commit 已經試過，不會再被自動部署（watcher 同一個 commit 只試一次）。

## 版本從哪裡查

| 來源 | 回答什麼 | 用在哪 |
|---|---|---|
| 機器上的映像檔（`docker images talkcal/app`，label `org.opencontainers.image.revision`） | **退得回去**的版本：映像檔在才退得了 | `--list`；所有退版前的檢查 |
| GitHub Deployments（watcher、rollback 寫的） | 哪個 commit 部署**成功**過、順序 | `--previous`：從新到舊找第一個「成功、不是現在這版、機器上有映像檔」的 |
| `.git/cd-<env>.history` | 這台機器上退過哪些版本、什麼時候 | 事後查；GitHub 連不上時唯一的紀錄 |
| release tag（`v0.2.0`，#25） | 人確認過、值得留名字的版本 | 指定版本時好記；`--list` 會列出 tag 名 |

為什麼「上一版」不用映像檔的建立時間排：build 有快取時，舊 commit 的映像檔建立時間可能比新的還晚
（示範時 build 三個版本，`aee2252` 的建立時間是前一天、`73d6cb5` 和 `local` 共用同一個時間）。
部署順序只有部署紀錄知道。

`--previous` 連按兩次會在兩版之間來回（跟 `kubectl rollout undo` 一樣：「上一版」是相對於現在這版）。要退更早的，指定版本。

## 資料庫：schema 不退

程式退得回去，資料庫退不回去，也**不該**退：

- Flyway 社群版沒有 undo；就算有，退 schema 就是丟掉新欄位裡的資料
- Flyway 預設 `ignoreMigrationPatterns=*:future`：資料庫的版本比程式新時，只記一行警告就照常啟動
  （驗證 N 的 log：`Schema "public" has a version (11) that is newer than the latest available migration (10) !`）

所以能不能退，取決於**上一版的程式在新的 schema 上能不能正常讀寫**。這正是 expand/contract 的規則：
每一支 migration 跑完，上一版照樣要能用，contract（刪舊欄位）要等到「不會再被退回去」才做。
規則、檢查清單、這次的演練見 [docs/database/zero-downtime-schema.md](database/zero-downtime-schema.md)（#18，PR #133 合併後才有這份）。

能退的範圍因此是有限的：**往前每 contract 一次，能退回的範圍就縮一格**。退之前看第 1 步的表。

真的退到了不相容的版本（驗證 N 那樣）：馬上再退回新版（`cd/rollback.sh local <新版>`），改走 hotfix。

## 退完之後，watcher 不會把新版部署回來

watcher（`cd/triggers/watch.sh`）只部署「master 最新的綠燈 commit、而且沒試過的」：

- **剛才壞掉的那個 commit** 已經試過，不會再自動部署 —— 這本來就成立
- **但下一個綠燈的 commit** 一出現就會部署。如果修正還沒進去（例如中間合併了別的 PR），壞掉的程式又上線了

所以 rollback 會**釘住**：寫一個 `.git/cd-<env>.pin`，watcher 每一輪先看它，在就不部署、log 寫「釘在 X」。
`--unpin` 才解除。手動部署（`./gradlew deploy`、`cd/apply.sh`）不看釘住：人自己決定要部署的，不擋。

| 做法 | 選了嗎 | 理由 |
|---|---|---|
| **釘住檔（`.git/cd-<env>.pin`）** | ✅ | 跨平台（EC2 之後也跑同一支 watcher）；檔案內容寫著釘在哪版、什麼時候、從哪版退的；rollback 自動釘，忘不了 |
| 停掉排程（`Disable-ScheduledTask talkcal-cd-local`） | 留著當緊急開關 | 只有 Windows；要另外記得；看不出為什麼被停 |
| 在 master 上 `git revert` 壞掉的 commit | 修正的正道，但不是止血 | 要開 PR、等 CI、等 watcher，十幾分鐘；釘住之後慢慢做 |

這跟 Argo CD 是同一件事：開著 automated sync 的 application 不能 rollback（[Argo CD 文件](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/)），
要先關掉自動同步，不然它馬上把叢集同步回 Git 上的最新版。2026 年有提案讓 auto-sync 認得 rollback
（[argo-cd#29926](https://github.com/argoproj/argo-cd/pull/29926)），但預設仍然是關的。

釘住只對**同一個 clone 的 watcher** 有效。在開發用的 clone 跑 rollback 時，`.git` 裡沒有 `cd-watch.sh`，腳本會提醒你換到部署用的 clone。

## 映像檔不在了

rollback 只用機器上已經有的映像檔（沒有 registry，見[選型](#選型)）。被刪掉的話（`docker image prune -a`、換了一台 EC2）：

```bash
# 在部署用的 clone：checkout 那個版本，照一般的部署流程重 build。之後記得 cd/rollback.sh local 釘住，或停掉排程
git checkout --detach <版本的 commit>
cd/apply.sh local
```

這不是嚴格的「退回」：同一個 commit 重 build，base image、依賴可能已經不一樣。所以不清 `talkcal/*` 的舊映像檔
（`docker image prune` 不帶 `-a` 只清沒有 tag 的，不影響）。

## EC2

EC2 還沒有 `cd/envs/ec2.env`（[cd/README.md](../cd/README.md#還沒做的)），`rollback.sh` 還不能直接用。
在機器上（SSM 連進去）手動做一樣的事：

```bash
cd /opt/talkcal/repo
F="-f deploy/compose.yaml -f infra/envs/1-ec2/compose.ec2.yaml -f infra/envs/1-ec2/compose.bedrock.yaml"
sudo docker image ls talkcal/app                    # 確認要退的版本在
for svc in web app; do
  sudo APP_VERSION=<版本> docker compose $F up -d --no-build --pull never --no-deps --wait $svc
done
```

EC2 沒有 watcher（手動部署），不用釘住。等 `ec2.env` 有了，換成 `cd/rollback.sh ec2 ...`。

## 選型

### 退版的單位：換回舊的映像檔

| 做法 | 多快 | 退回的是不是「當時那個東西」 | 選了嗎 |
|---|---|---|---|
| **換回機器上舊 tag 的映像檔** | 幾十秒（只等 healthcheck） | 是：同一個映像檔 | ✅ |
| checkout 舊 commit、重 build | 幾分鐘 | 不一定：base image、依賴可能變了 | 映像檔不在時的退路 |
| `git revert`、走一般流程 | 十幾分鐘（PR、CI、watcher） | 是新的 commit | 修正的正道，不是止血 |
| 推到 registry（ECR）、用 digest 部署 | 跟第一個一樣 | 是，而且換機器也在 | 還沒有 registry；上 EKS（#58）時一起做 |

業界的共識是：rollback 要能指到**不可變的**映像檔（commit tag 或 digest），`latest`、`local` 這種會被覆蓋的 tag，
「退回」拉到的可能還是壞掉的那個（[2026-09 的案例](https://oneuptime.com/blog/post/2026-09-26-rollout-undo-mutable-image-tag-bad-build/view)）。
#25 之前的 `talkcal/app:local` 就是這個問題：每次部署都覆蓋同一個 tag，舊版本不存在。

### 為什麼 `--no-build --pull never`

- 不帶 `--no-build`：compose 會拿**目前 checkout 的程式碼** build、貼上舊版本的 tag —— 名字是舊的，內容是新的，等於沒退
- 不帶 `--pull never`：機器上沒有的版本，compose 會去 Docker Hub 找 `talkcal/app`，那不是這個專案的 namespace
- 兩個都帶了，又先檢查映像檔在不在：不在就在動任何東西之前結束（驗證 B、C）

### 為什麼先換 web 再換 app

部署（`apply.sh`）是先 app 後 web；退版反過來。中間那一小段：

- 先 web：「舊前端 + 新後端」。新後端本來就要相容舊的呼叫方式（跟 schema 同一個 expand/contract 的道理）
- 先 app：「新前端 + 舊後端」。新前端可能打到舊版還沒有的 API

### 起不來就換回原本的

退版通常是在服務已經出問題時做的，不能再弄出一個「完全掛掉」：
`--wait` 逾時就把 web、app 都換回退之前的版本（驗證 G）。換回也失敗才會停在壞的狀態，訊息會說怎麼手動部署。

### 不做自動 rollback

部署後偵測錯誤率、自動退版（ECS circuit breaker、Argo Rollouts 的 analysis）要先有「這版壞了」的判斷依據。
現在唯一的依據是 healthcheck，它擋不住「起得來但功能壞了」（驗證 N 就是 healthy 但寫入全壞）。
拿它當自動退版的條件，只會在「容器起不來」時觸發；那種情況 watcher 已經把部署記成失敗、Deployments 上是紅的，人看到後用這支退。
自動退版留給 canary（[#20](https://github.com/Anderson-chen/talkcal/issues/20)）：有 SLO、有對照組，才知道該不該退。

## 驗證（2026-10-16，本機）

在 #25 的分支（`ae12882`）上加這支 rollback 示範，用另一個 compose 專案 `talkcal-rbdemo`（不同埠、不同 volume），
不碰本機正在跑的 `talkcal-deploy`。三個版本的映像檔照 `apply.sh` 的做法 build（tag = 版本號、label 帶 commit）：
`ae12882`（新，#25）、`73d6cb5`（舊，master）、`aee2252`（更舊，V11 之前）。GitHub 的 token 故意給壞的，不寫真的 Deployments。

| | 情況 | 結果 |
|---|---|---|
| A | `--list` | 列出 app、web 都在的版本，標出現在跑的 |
| B | 反例：不存在的版本 `v9.9.9` | 擋下，結束碼 1，「什麼都沒動」；服務照舊 healthy（`ae12882`） |
| C | 反例：只有 app、沒有 web 的版本 | 擋下，同上 |
| D | 反例：`../../etc`、`--bogus` | 擋下（不合法的版本號／用法） |
| E | 正常：`ae12882` → `73d6cb5` | web、app 依序換掉，healthy，寫下釘住 |
| F | 再退一次同一版 | 「不用退」，結束碼 0 |
| G | 反例：退到起不來的版本（healthcheck 永遠不過） | app unhealthy → 自動換回 `73d6cb5`，health 200，結束碼 1，釘住維持原狀 |
| J | 反例：watcher 正在部署（鎖在） | 擋下，什麼都沒動 |
| K | 在部署用的 clone 裡退：`73d6cb5` → `ae12882` | 成功；`/actuator/info` 回 `ae12882` |
| L | 釘住後，排程跑的 `.git/cd-watch.sh` | 「釘在 ae12882 …，不自動部署」，結束碼 0 |
| M | `--unpin` 後 watcher | 越過釘住，往下去問 GitHub |
| N | 跨 migration：`ae12882`（資料庫 V11）→ `aee2252`（程式到 V10） | 退成功、容器 healthy，Flyway 只警告 schema 比較新；**但新增行程 502**（`null value in column "owner_id"`），而且列表**讀得到別人的行程**（#104 之前沒有依使用者分開）—— 第 1 步那張表的兩列就是從這裡來的 |
| P | `--previous`，假的 Deployments：現在這版成功、機器上沒有映像檔的成功、`aee2252` 失敗、`73d6cb5` 成功 | 選 `73d6cb5`；寫一筆 Deployment 和 success 狀態 |
| Q | 反例：`--previous` 但 gh 連不上 | 擋下，結束碼 1，什麼都沒動（第一版沒擋，repo 是空的還一路往下查，修了） |

沒驗到的：真的寫進 GitHub Deployments（P 用的是假的 gh）；EC2 上的手動指令。

## 還沒做的

- `cd/envs/ec2.env`：EC2 也能直接 `cd/rollback.sh ec2 ...`（跟 cd/README.md 的同一項）
- registry（ECR）＋ digest：映像檔不再只存在一台機器上；上 EKS（#58）時一起做
- 映像檔保留規則：現在不清舊版本，久了會佔空間；之後留最近 N 個
- 部署後的 smoke test（`verify.sh`）：退完自動走一次登入、讀寫行程，驗證 N 那種「healthy 但壞了」才會被抓到
