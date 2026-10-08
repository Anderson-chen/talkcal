# ci —— 持續整合（CI）

每次改動都在**乾淨的容器**裡自動檢查一遍：後端的單元測試、架構規則、整合測試，前端的型別檢查和打包。
本機（push 之前）和 GitHub（push 之後）跑的是**同一支 script、同一個映像檔**，所以本機綠就代表 GitHub 綠。

## 怎麼跑

```bash
ci/run.sh                               # 手動跑一次，測 HEAD
git config core.hooksPath .githooks     # 啟用 pre-push hook（每個 clone 做一次）
git push --no-verify                    # 確定要跳過檢查時
```

要先開著 Docker Desktop。llama-server 有開的話，模型相關的測試會真的跑；沒開就跳過，摘要會列出來。
第一次跑要 build 映像檔（含 Gradle 本體）、下載依賴，比較慢；之後映像檔有快取、依賴放在 volume `talkcal-ci-gradle`、`talkcal-ci-npm` 裡，就快了。
結果：摘要在 `ci/build/summary.md`，失敗時的 HTML 報告在 `ci/build/reports/`。

## 架構：檢查內容只寫一次，平台只是入口

```
.github/workflows/ci.yml ─┐
                          ├──▶ ci/run.sh ──▶ 容器（ci/Dockerfile）裡跑 ci/checks.sh
.githooks/pre-push ───────┘     在主機上       在容器裡
```

| 檔案 | 回答的問題 | 換平台時 |
|---|---|---|
| `checks.sh` | **要檢查什麼** | 不動 |
| `Dockerfile` | **在什麼環境檢查**（JDK 25、Node 22、Gradle 本體） | 不動 |
| `run.sh` | 怎麼把程式碼放進容器、跑完怎麼拿回結果 | 不動 |
| `.github/workflows/ci.yml` | **什麼時候、在哪台機器跑**，結果貼到哪 | 換成 `.gitlab-ci.yml`、`bitbucket-pipelines.yml`、`Jenkinsfile` |

跟後端的六角架構同一個道理：`checks.sh` 是核心，各平台的設定檔是 adapter。
`ci.yml` 若自己寫一串 `setup-java`、`./gradlew test`，就等於把業務規則寫進 adapter ——
換到 GitLab 要整份重寫，本機的 hook 也得另外抄一份，兩邊遲早會不一樣。

## 為什麼在容器裡跑，而不是直接用主機

直接在主機跑會遇到的事：

| 問題 | 例子 | 容器怎麼解決 |
|---|---|---|
| 測到的不是要推的東西 | 忘了 `git add` 的檔案讓本機測試過了，推上去才紅 | 用 `git archive` 只複製那個 commit 進去 |
| 環境不一樣 | 主機預設是別版的 JDK、Node，或有個人的 Gradle 設定 | JDK、Node 版本只定義在 `Dockerfile` |
| 殘留狀態 | 舊的 `build/`、`node_modules` 讓結果看起來沒事 | 每次都是新容器，跑完就刪 |

代價和處理：

| 代價 | 處理 |
|---|---|
| 每次全新環境，東西要重新下載 | 很少變的（JDK、Node、Gradle 本體）烤進映像檔；常變的（Java 函式庫、npm 套件）放具名 volume，跨次保留；程式碼兩邊都不放 |
| Windows 把資料夾掛進容器很慢（實測約 20 MB/s） | 不掛資料夾，用 `git archive` 複製 |
| 整合測試要開 PostgreSQL（Testcontainers） | 把主機的 `docker.sock` 接進來，PostgreSQL 開在主機的 Docker 上、跟 CI 容器並排，不在容器裡再包一層 |
| 模型測試要連 llama-server | 經 `host.docker.internal:8080` 連主機上的；要連別台就設 `SPRING_AI_OPENAI_CHAT_BASEURL` |

## 為什麼本機和 GitHub 都要跑

| | 本機（pre-push） | GitHub Actions |
|---|---|---|
| 有沒有模型 | 有（llama-server 開著時） | 沒有 GPU，模型測試跳過 |
| 擋得住什麼 | 推之前就擋，壞掉的東西不會上去 | 不管從哪條路進來（push、PR）都會跑，不靠人記得 |
| 誰看得到 | 只有自己 | 公開：commit 旁的 ✅ ❌、README 的徽章 |

兩邊各守一道：本機補上 GitHub 跑不到的模型測試，GitHub 補上本機可以 `--no-verify` 繞過的那一道。

## 刻意不做的

- **k6 壓測**：要真模型，CI 量不出有意義的數字。見 [perf/README.md](../perf/README.md)。
- **GitHub 上的依賴快取**：每次都是新機器、重新下載，比較慢。先求跟本機一致，嫌慢再把 `/cache` 接上 `actions/cache`。
- **自架 runner 讓 GitHub 也跑模型測試**：公開 repo 接自架 runner，別人的 PR 會在這台電腦上執行程式碼；
  要做的話只能接自己的 push，而且要另外做安全設定。目前由本機的 pre-push 補這一塊。
