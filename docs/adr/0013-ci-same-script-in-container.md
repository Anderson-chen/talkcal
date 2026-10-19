# 0013. CI 的檢查只寫在 `ci/checks.sh`，在容器裡跑；本機 pre-push 和 GitHub Actions 都呼叫它

- 狀態：已採用
- 日期：2026-10-08
- 相關：[d89c076 CI 改成在乾淨的容器裡跑](https://github.com/Anderson-chen/talkcal/commit/d89c076)、epic [#17](https://github.com/Anderson-chen/talkcal/issues/17)、[0010](0010-hexagonal-with-archunit.md)

## 背景

GitHub Actions 沒有 GPU，跑不到連真模型的測試；本機有 GPU，但推之前可以 `--no-verify` 繞過。
直接在主機跑檢查會遇到：忘了 `git add` 的檔案讓測試過了、主機的 JDK / Node 版本不一樣、舊的 `build/` 讓結果看起來沒事。

## 選項

檢查寫在哪：

| 選項 | 代價 |
|---|---|
| 寫在 `ci.yml` 裡（`setup-java`、`./gradlew test`…） | 換到 GitLab 要整份重寫；本機 hook 要另外抄一份，兩邊遲早不一樣 |
| 寫在一支腳本，各平台的設定檔只是入口 | 多一層間接 |

在哪跑：

| 選項 | 代價 |
|---|---|
| 直接用主機 | 上面三個問題 |
| 容器（`git archive` 把那個 commit 放進去） | 每次全新環境要重新下載；Windows 掛資料夾進容器很慢（實測約 20 MB/s） |

## 決定

**`checks.sh` 是「要檢查什麼」、`Dockerfile` 是「在什麼環境檢查」、`run.sh` 負責把 commit 放進容器；`.github/workflows/ci.yml` 和 `.githooks/pre-push` 都只呼叫 `ci/run.sh`。**

跟後端的六角架構同一個道理：`checks.sh` 是核心，各平台的設定檔是 adapter（[0010](0010-hexagonal-with-archunit.md)）。
兩邊各守一道：本機補上 GitHub 跑不到的模型測試，GitHub 補上本機可以繞過的那一道。

容器的代價這樣處理：很少變的（JDK、Node、Gradle 本體）烤進映像檔；常變的（Java 函式庫、npm 套件）放具名 volume；
不掛資料夾，用 `git archive` 複製；Testcontainers 接主機的 `docker.sock`，不在容器裡再包一層。

## 代價

- 模型測試只在本機真的跑；GitHub 上那幾支是跳過。
- 本機的 pre-push 要等一輪完整的檢查才推得上去。

## 什麼時候重想

- CI 有 GPU 機器可用 → 模型測試也搬上 CI。
- 換 CI 平台 → 只寫新平台的設定檔呼叫 `ci/run.sh`，檢查內容不動。

## 證據

- [ci/README.md](../../ci/README.md)：架構、為什麼在容器裡跑、為什麼兩邊都要跑
- [ci/checks.sh](../../ci/checks.sh)、[ci/run.sh](../../ci/run.sh)、[ci/Dockerfile](../../ci/Dockerfile)
- [.github/workflows/ci.yml](../../.github/workflows/ci.yml)、[.githooks/pre-push](../../.githooks/pre-push)
