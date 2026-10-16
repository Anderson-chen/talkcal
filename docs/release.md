# 版本與 release tag

回答一個問題：**線上現在跑的是哪個 commit？** rollback（[#19](https://github.com/Anderson-chen/talkcal/issues/19)）
和 hotfix（[docs/hotfix.md](hotfix.md)，[#26](https://github.com/Anderson-chen/talkcal/issues/26)）都從這裡起步：
要退回去或從線上那一版開分支，得先說得出線上是哪一版。

以前說不出來：沒有 git tag，映像檔固定叫 `talkcal/app:local`，每次部署都蓋掉同一個名字。
GitHub Deployments 記的是「watcher 想部署哪個 commit」，不是「容器裡實際跑的是哪個」——
手動 `./gradlew deploy` 一次，兩邊就對不上了。

## 版本號長什麼樣

規則寫在 [cd/version.sh](../cd/version.sh)，只有這一份：

| 情況 | 版本號 | 例子 |
|---|---|---|
| HEAD 上有 release tag（`v<主>.<次>.<修>`） | 那個 tag | `v0.2.0` |
| 沒有 tag | commit 前 7 碼 | `3f9c2ab` |
| 有沒 commit 的改動或新檔案 | 上面兩種後面加 `-dirty` | `3f9c2ab-dirty` |
| 沒經過部署流程（`bootRun`、IDE、直接 `docker build`） | `local`，commit 是 `unknown` | `local` |

不管哪一種，**完整的 40 碼 commit 永遠另外記一份**（`/actuator/info` 的 `build.commit`、映像檔 label 的 `revision`）。
版本號是給人看的名字，commit 才是身分。

### 為什麼這樣選

| 方案 | 每次部署都分得出來 | 看名字就知道是哪個 commit | 本機和 EC2 算出一樣的值 | 適合這個專案嗎 |
|---|---|---|---|---|
| 固定 `:local`（原本） | ✗ | ✗ | ✓ | 說不出線上是哪一版 |
| `:latest` 或環境名（`:prod`） | ✗ | ✗ | ✓ | 同上，會被下一次 build 蓋掉 |
| 只用 commit SHA | ✓ | ✓ | ✓ | 可以，但「這一版是確認過、可以退回來的」沒地方標 |
| 只用 semver tag | ✗：CD 每個綠燈 commit 都部署，不會每個都打 tag | 要查 | ✓ | 兩次 release 之間的部署全部沒名字 |
| `git describe`（`v0.2.0-3-g3f9c2ab`） | ✓ | ✓ | **✗**：EC2 是 `--depth 1` 淺 clone，算不出「離 tag 幾個 commit」 | 同一個 commit 在兩台機器名字不同 |
| CalVer（`2026.10.16`） | 一天多次部署要再加序號 | ✗ | 要另外存「今天第幾次」 | 版本號本身不帶 commit |
| **tag 有就用 tag，沒有用 SHA（選這個）** | ✓ | ✓（tag 也是指著一個 commit） | ✓：只看「指著 HEAD 的 tag」，淺 clone 也拉得到 | 每次部署都有名字，確認過的那幾個另外有好記的名字 |

業界的共識（2026 年查的，見下面來源）是兩件事一起做：每個 build 一個不可變、帶 commit 的 tag 方便追溯；
正式 release 另外用 semver tag。這裡沒有 registry，兩件事收成一個名字：有 release tag 時就是 release tag。

**為什麼是 semver 不是 CalVer**：talkcal 沒有外部的 API 使用者，「主版號代表不相容」的意義不大；
但 semver 有清楚的「修補版」——hotfix（[docs/hotfix.md](hotfix.md) 情境 B）從 `v0.2.0` 修出來的就是 `v0.2.1`，
一看就知道它是哪一版的修補。CalVer 只說得出日期，說不出它跟哪一版是同一條線。

**為什麼 `-dirty` 要誠實寫出來**：`./gradlew deploy` 是從開發用的 clone 部署，可能有還沒 commit 的改動。
這時候映像檔裡的程式碼不等於任何一個 commit，掛著乾淨的 SHA 會讓人以為 checkout 那個 commit 就能重現。
被 `.gitignore` 的檔案（`deploy/.env`、`build/`）不算：git 本來就不列它們。

## 版本帶到哪裡

```
git（tag、HEAD）
  └─ cd/version.sh ─────────────── 算出 APP_VERSION、GIT_COMMIT
       └─ cd/apply.sh（本機）／infra/envs/1-ec2/deploy.sh（EC2）export 給 compose
            └─ deploy/compose.yaml
                 ├─ image: talkcal/app:<版本>、talkcal/web:<版本>     ← docker images 看得到
                 └─ build.args → Dockerfile
                      ├─ LABEL org.opencontainers.image.version / revision   ← docker inspect 看得到
                      └─ gradle -PappVersion -PgitCommit → build-info.properties（jar 裡）
                           └─ /actuator/info                         ← curl 看得到
```

映像檔 build 的 context 是 `backend/`、`web/`，看不到 `.git`，所以不在 Dockerfile 或 Gradle 裡跑 git，
一律從外面傳進去。同一個值走三條路是因為三種情況下各只看得到其中一種：
容器沒在跑時只能看映像檔（tag、label），容器在跑但不確定是哪個映像檔時問 app 自己（info）。

### 怎麼查線上是哪一版

```bash
# 本機
curl -s http://127.0.0.1:18090/actuator/info
#   {"build":{"artifact":"talkcal","name":"talkcal","commit":"9dc4eb9…","version":"9dc4eb9"}}
docker inspect talkcal-app --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'

# EC2：先用 SSM 連進去（infra/envs/1-ec2/README.md），在機器上跑同樣兩行
```

這比 GitHub Deployments 準：Deployments 記的是 watcher「想部署」的 commit，這裡是容器裡**實際在跑**的。

### 為什麼不對外公開

`/actuator/info` 只在容器網路和本機 `127.0.0.1:18090` 看得到；網站入口的 nginx 只轉 `/api/`，
`https://<網址>/actuator/info` 是 404。前端也只把版本貼在映像檔 label，不寫進頁面。

- 版本號是給維運的人查的，使用者用不到
- repo 是公開的，精確的 commit 等於告訴別人「線上跑的就是這一份程式碼」，哪個依賴有洞一查就知道
- 要給面試官看「哪個版本部署到哪」，GitHub 的 Deployments 和 Releases 頁面就有

`info` 只放 `build` 這一段：Boot 的 `env`、`java`、`os`、`process` contributor 預設是關的，這裡沒打開
（[ActuatorInfoTest](../backend/src/test/java/io/github/andersonchen/talkcal/app/observability/ActuatorInfoTest.java) 守著這件事，也守著 `/actuator/env` 依然拿不到）。

## 怎麼打 release tag

```bash
cd/release.sh v0.2.0                          # 打在 origin/master 最新的 commit
cd/release.sh v0.2.1 origin/hotfix/<分支>      # hotfix（docs/hotfix.md 情境 B）
```

[cd/release.sh](../cd/release.sh) 先檢查，過了才在**本機**建 annotated tag，最後印出推上去和開 GitHub Release 的指令：

```bash
git push origin v0.2.0
gh release create v0.2.0 --verify-tag --generate-notes   # 依合併的 PR 自動產生 release notes
```

擋下來的情況（每一條都是推出去之後很難收回的）：

| 檢查 | 為什麼 |
|---|---|
| 名字是 `v<主>.<次>.<修>` | 其他名字 `cd/version.sh` 不會當成版本 |
| 本機和 GitHub 上都還沒有這個 tag | tag 推出去就不該移動：同一個名字指到兩個 commit，「線上是 v0.2.0」就沒意義了 |
| commit 在 `origin/master` 或 `origin/hotfix/*` 上 | 沒走過 PR 或 hotfix 流程的東西不該變成 release |
| 那個 commit 的 CI（push 事件）綠燈 | 跟 watcher 部署的條件一樣 |
| 版本號比「這個 commit 之前最近的 tag」大 | 跟所在那條線比，不跟全部比：hotfix 線上的 `v0.2.1` 可以在 `v0.3.0` 之後出現 |

### 什麼時候打、打在哪

- 打在**已經部署、確認過**的 commit 上：tag 的意思是「這一版是好的，出事可以退回這裡」。
  CD 照樣每個綠燈 commit 都部署，不是每個都要打 tag
- 版號怎麼跳：加功能跳次版號（`v0.2.0` → `v0.3.0`），只有修正跳修補號（`v0.2.0` → `v0.2.1`）。
  還沒到 1.0：介面和資料表都還可能大改
- 打了 tag 之後，線上那個容器的版本號還是 SHA（它 build 的時候 tag 還不存在）。commit 一樣就是同一版，
  下一次從這個 commit build（例如 rollback 回來）就會叫 `v0.2.0`

### 為什麼是手動，不是 workflow 自動打

| | 每次合併自動打（semantic-release、release-please） | **手動跑 cd/release.sh（選這個）** |
|---|---|---|
| 版號怎麼決定 | 從 commit 訊息推（要 Conventional Commits 的 `feat:`、`fix:`） | 人決定 |
| 「這一版確認過」 | 做不到：合併當下還沒部署 | 部署、看過之後才打 |
| 要的權限 | workflow 要能推 tag（`contents: write`） | 用自己的 `gh` 登入 |
| 適合 | 發佈套件、release 很頻繁的團隊 | 一個人、偶爾 release 的服務 |

commit 訊息用的是這個 repo 自己的 `[feat]`、`[fix]`（見 CLAUDE.md），不是 Conventional Commits，自動工具也推不出版號。
檢查交給腳本，按下去的時機留給人。

## 給 rollback（#19）的介面

這次只做到「每個版本有自己的映像檔 tag，舊的留在機器上」，rollback 本身留給 #19：

- `deploy/compose.yaml` 的 `image: talkcal/app:${APP_VERSION}`：設好 `APP_VERSION` 再 `up --no-build`，
  就是換回機器上已經有的那個版本，不用重 build
- `cd/apply.sh` **不**接受從外面指定 `APP_VERSION`：它一定 `--build` 目前 checkout 的程式碼，
  讓外面指定的話會把 A 版的程式碼 build 成 B 版的名字
- 舊映像檔會一直累積（每個約幾百 MB），#19 要決定留幾個、怎麼清

## 已知限制

- **還不是 build once**：本機和 EC2 各自從原始碼 build，同一個 commit 是兩個不同的映像檔
  （名字一樣，base image 的修補可能不同）。要做到 build once 得把映像檔推到 registry（ECR、GHCR），
  部署改成拉指定 tag；目前一台 EC2、平常關機，先不做
- **tag 沒有被保護**：GitHub 的 ruleset 可以禁止刪除、移動 `v*` tag，這是 repo 設定，要使用者自己決定要不要開
- GitHub Deployments 記的還是 commit，不是版本號

## 驗證紀錄（2026-10-16）

每一項都造了「該抓的」和「不該被誤抓的」。

**版本號規則**（在 scratchpad 的 clone 裡造 tag，不碰真的 repo）：

| 情況 | 結果 |
|---|---|
| 沒有 tag | `9dc4eb9` |
| 只有 `exp-1`、`v1.2`、`v0.11.0-rc1` 這類不是正式版號的 tag | `9dc4eb9`（不當成版本） |
| lightweight `v0.9.0` | `v0.9.0` |
| 同一個 commit 再加 `v0.10.0` | `v0.10.0`（依版本號排，不是依字母：`v0.10.0` > `v0.9.0`） |
| tag 之後多一個 commit | `b9b0b0b`（不沿用前一個 tag） |
| 改了追蹤中的檔案 | `b9b0b0b-dirty` |
| 多一個沒追蹤的新檔案 | `b9b0b0b-dirty` |
| 只有被 ignore 的 `deploy/.env` | `b9b0b0b`（不誤判） |
| 模擬 EC2：`git fetch --depth 1 --tags` 拉有 `v0.11.0` 的 commit | `v0.11.0`（淺 clone 也認得） |
| 模擬 EC2：淺 clone 拉沒 tag 的 commit | `0f36f0e`，跟完整 clone 算出來的一樣 |

**從 git 帶進映像檔和 app**（`docker compose build app web`，沒有換掉正在跑的容器）：

```
image:   talkcal/app:9dc4eb9、talkcal/web:9dc4eb9
label:   talkcal-app version=9dc4eb9 revision=9dc4eb90bbc46258d4859b28d423d08467fc658e
         talkcal-web version=9dc4eb9 revision=9dc4eb90bbc46258d4859b28d423d08467fc658e
/actuator/info → {"build":{"artifact":"talkcal","name":"talkcal","commit":"9dc4eb90bbc4…","version":"9dc4eb9"}}
/actuator/env → 404   /actuator/beans → 404
```

**反例：沒帶版本直接 `docker build backend`**：label `version=local revision=unknown`，
info 回 `{"version":"local","commit":"unknown"}` —— 不會冒充某個版本。

**網站入口連不到**：`127.0.0.1:18080/actuator/info` → nginx 404；`/api/../actuator/info`、`/api/%2e%2e/actuator/info` 也是 404。

**cd/release.sh**（在本機建 tag 後立刻刪掉，沒有推任何 tag）：

| 輸入 | 結果 |
|---|---|
| `1.0`、`v0.1` | ✗ 版本號格式 |
| `v0.1.0 HEAD`（這條 PR 分支，不在 master） | ✗ 不在 origin/master 或 origin/hotfix/* 上 |
| `v0.1.0 867cafe`（master 上沒有 CI 綠燈的 commit） | ✗ 沒有綠燈的 CI |
| `v0.1.0`（origin/master 最新、CI 綠燈） | ✓ 建好本機 tag，印出推上去的指令 |
| 再跑一次 `v0.1.0` | ✗ 本機已經有 v0.1.0 |
| 先在 `origin/master~1` 放 `v0.2.0`，再跑 `v0.1.5` | ✗ 沒有比上一個 v0.2.0 大 |

「GitHub 上已經有這個 tag」那條沒驗到：GitHub 上目前一個 tag 都沒有，驗它得真的推一個上去。

## 來源

- [How to Handle Docker Image Tagging（OneUptime，2026-02）](https://oneuptime.com/blog/post/2026-02-02-docker-image-tagging/view)：每個 build 帶 commit SHA 的不可變 tag，release 另外用 semver，部署不用 `latest`
- [Image tag best practices（Microsoft Learn，Azure Container Registry）](https://learn.microsoft.com/en-us/azure/container-registry/container-registry-image-tag-version)：穩定 tag 與唯一 tag 的差別；只有 commit 的 tag 是「半穩定」——同一個 commit 重 build 會換 base image
- [Tag and manage Docker image versions in Artifact Registry（OneUptime，2026-02）](https://oneuptime.com/blog/post/2026-02-17-how-to-tag-and-manage-docker-image-versions-in-artifact-registry/markdown)：不可變 tag、用唯一 tag 或 digest 部署
- [Spring Boot 4.1 Gradle plugin：Integrating with Actuator](https://docs.spring.io/spring-boot/4.2/gradle-plugin/integrating-with-actuator.html)、[actuator info 端點](https://docs.spring.io/spring-boot/4.0/api/rest/actuator/info.html)：`buildInfo()` 產生 `build-info.properties`，info 端點自動讀
- [OCI image annotations](https://github.com/opencontainers/image-spec/blob/main/annotations.md)：`org.opencontainers.image.version`、`revision`、`source` 這幾個 label 名稱
