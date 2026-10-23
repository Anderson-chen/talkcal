# 套件升級

套件由 [Renovate](https://docs.renovatebot.com/) 自動開 PR 升級，設定在 [`.github/renovate.json5`](../.github/renovate.json5)，
為什麼選 Renovate 不選 Dependabot 見 [ADR 0016](adr/0016-renovate-for-dependency-updates.md)。
目的（[#48](https://github.com/Anderson-chen/talkcal/issues/48)）：套件不會舊到升不動，安全修補跟得上。

## 涵蓋哪些地方

| 在哪 | Renovate 怎麼看到 |
|---|---|
| `backend/build.gradle`、`gradle-wrapper.properties` | 內建 `gradle`、`gradle-wrapper` |
| `web/package.json` ＋ 鎖檔（npm 或 pnpm，看 repo 裡是哪一個鎖檔；`packageManager` 欄位的 pnpm 版本也會升） | 內建 `npm` |
| `backend/`、`web/`、`ci/` 的 Dockerfile | 內建 `dockerfile` |
| `deploy/`、`ops/`、`infra/envs/1-ec2/` 的 compose | 內建 `docker-compose` |
| `.github/workflows/*.yml` | 內建 `github-actions` |
| `infra/` 的 Terraform provider（連 `.terraform.lock.hcl`） | 內建 `terraform` |
| `ci/secrets.sh`、`ci/vulns.sh` 裡的 gitleaks、Trivy（版本＋digest） | 自訂 regex |
| llama.cpp 的 build 號（Dockerfile 的 `ARG` 和 compose 的 tag 一起改） | 自訂 regex |
| `docs/architecture/index.html` 抄的映像檔版本 | 自訂 regex，跟 compose 在同一個 PR 改 |

刻意不升的：CUDA（跟主機的 GPU 驅動綁在一起）、nginx 的 mainline（奇數小版本）、TypeScript 6 以上（vue-tsc 還接不上，見 [web/README.md](../web/README.md)）。

## 什麼時候、開成什麼樣的 PR

| 種類 | 何時開 | 分組 |
|---|---|---|
| 安全修補（OSV 資料庫查到漏洞） | **立刻**，不等排程、不等 3 天 | 單獨一個 PR，label `security` |
| minor、patch | 週一早上（台灣時間 9 點前），新版發佈滿 3 天 | 依生態系各一個：後端、前端、映像檔、觀測堆疊、Terraform |
| 要對齊的 | 同上 | Gradle（wrapper＋映像檔）、JDK（各處 temurin）、Node（各處 node） |
| GitHub Actions | 同上 | 全部一個 PR（含 major） |
| major | **不自動開**，列在 Dependency Dashboard | 勾了才開，一個套件一個 PR，label `major` |
| 鎖檔整理（間接依賴） | 每月 1 號 | 一個 PR，只動鎖檔 |

同時最多 5 個 PR、每小時最多 2 個；超過的排在 Dependency Dashboard（Renovate 開的一個 issue）上。

## 誰審、怎麼合

**不自動合併。** 每個 Renovate 的 PR 都由使用者（repo 的維護者）審、按合併：

1. **CI 綠燈才合**：`checks`、`secrets`、`vulns` 都是必過（ruleset `protect-master`）。
   `pr-issue` 對 Renovate 的 PR 會跳過（它們沒有對應的 issue，追蹤看 Dependency Dashboard），跳過算通過。
   ruleset 要求分支跟 master 同步，Renovate 會自己 rebase。
2. **看 release notes**：Renovate 的 PR 描述附了每個套件的 changelog 連結。patch 看有沒有寫 breaking；minor 看有沒有棄用（deprecation）。
3. **改到 `deploy/`、`ops/`、`infra/` 的 PR**：照 [CLAUDE.md](../CLAUDE.md) 的規則對一次架構頁。
   映像檔版本 Renovate 已經一起改了，人要看的是說明文字（例如「Caddy 已出到 2.11.x，這裡仍釘 2.10.0」）和頁尾的「最後核對」日期。
4. **要本機驗的**：CI 跑不到的東西要自己跑過才合 ——
   - 觀測堆疊：`ops/compose.yaml` 起來，Grafana 的 dashboard 還畫得出來。
   - Caddy（勾選才開）：EC2 上 HTTPS 憑證流程走一次。
   - llama.cpp（勾選才開）：重 build 映像檔、跑模型測試（本機 GPU）。
5. **紅燈**：不是在 Renovate 的 PR 上硬修。開一個 issue 記下哪個套件、為什麼壞，修好之後 Renovate 會自己 rebase；
   暫時升不了就在設定裡加一條 `allowedVersions` 並寫理由（像 TypeScript 那條），不要讓 PR 一直開著。

### major 怎麼處理

1. Dependency Dashboard 上看到有 major 可升 → 先讀 migration guide，評估要改多少。
2. 要做：勾選 → Renovate 開 PR。需要改程式碼的話，在同一個分支上 push 修改（或另外開 issue＋分支，把 Renovate 的 PR 關掉）。
3. 要一起動的：JDK major 還要改 `build.gradle` 的 `toolchain`；Node major 要確認本機開發用的 Node 也一起換；
   PostgreSQL major 要資料遷移（`pg_upgrade` 或倒出再匯入），不能只換 tag。
4. 暫時不做：留在 Dashboard 上不勾，它不會吵。

### AI 寫的 PR 跟 Renovate 的 PR

這個 repo 的程式碼多半是 AI 寫的；Renovate 的 PR 既不是 AI 也不是人寫的，是照設定機械產生的。
如果之後有「AI 寫的 PR 要人工審查」的檢查（[#28](https://github.com/Anderson-chen/talkcal/issues/28)，靠 `ai-generated`、`human-written` label 判斷），
要把 `renovate[bot]` 一起排除或給它自己的 label，不然 Renovate 的 PR 會被那道檢查擋住。
Renovate 的 PR 如果被 AI 接手改了程式碼（例如 major 要配合改），那些 commit 就照 AI 的 PR 審。

## 第一次啟用

要使用者自己做（裝 App 是授權第三方存取 repo，AI 不代做）：

1. 到 [github.com/apps/renovate](https://github.com/apps/renovate) 安裝，只選 `talkcal` 這個 repo。
2. repo 裡已經有 `.github/renovate.json5`，Renovate 不會再開 onboarding PR，直接照設定跑；第一次會開 Dependency Dashboard issue。
3. （選擇性）repo 設定裡開 Dependabot **alerts**（不是 Dependabot 的 PR）：漏洞會多一個來源，Renovate 也讀得到。

## 怎麼驗證設定

### 1. 設定合法（validator）

```bash
npx --yes --package renovate@44.167.5 -- renovate-config-validator --strict
```

2026-10-19 實測（在 repo 根目錄跑，它會自己找到 `.github/renovate.json5`）：

| 情況 | 結果 |
|---|---|
| 這份設定 | `Config validated successfully`，exit 0 |
| 反例：`prConcurrentLimit` 拼成 `prConcurentLimit` | `Invalid configuration option: prConcurentLimit`，exit 1 |
| 反例：`automerge: 'no'` | ``Configuration option `automerge` should be boolean``，exit 1 |
| 反例：`matchUpdateTypes: ['minr', …]` | **沒擋**，exit 0 |
| 反例：regex 的 `(?<currentValue>…)` 拼成 `currentValu` | **沒擋**，exit 0 |

validator 只看欄位名稱和型別，值對不對、regex 抓不抓得到東西它不管 —— 所以還要第二道。

### 2. 實際抓到什麼（本機 dry-run）

Renovate 在 Windows 上直接跑會因為 `re2` 模組崩潰，用官方映像檔跑：

```bash
docker run --rm -v "$PWD:/repo" -w /repo -e LOG_LEVEL=debug -e LOG_FORMAT=json -e RENOVATE_GITHUB_COM_TOKEN="$(gh auth token)" renovate/renovate:44.167.5 renovate --platform=local --dry-run=lookup > dryrun.jsonl
```

`--platform=local` 只讀檔案、不開 PR、不寫任何東西；token 只用來查 GitHub 上的版本（Actions、llama.cpp、Terraform），沒有的話那些會被跳過。
看 log 裡 `"msg":"packageFiles with updates"` 那一筆：每個檔案抓到哪些套件、目前版本、可以升到哪、會進哪個分支。

dry-run 抓到的問題（validator 都沒擋）：

- **分組全部擠成一個 PR**：`groupName` 用中文，Renovate 拿它產生分支名時把中文拿掉，五組都變成 `renovate/minorpatch`。
  補上 `groupSlug` 之後是 9 個分支，各組分開。
- **Docker 映像檔的更新全部卡在等待**：Docker Hub 不登入只給翻前 10 頁 tag，第 11 頁回 403，
  拿不到發佈時間的版本在 `minimumReleaseAge` 下會一直被當成「還沒滿 3 天」。docker 改成拿不到時間就不等。
- **nginx 會被升到 mainline**：1.30 是 stable 線（`web/Dockerfile` 的註解寫了為什麼不用 mainline），Renovate 不分，把 1.31 當成 minor。
  加 `allowedVersions` 只允許偶數小版本之後，nginx 變成「已是最新」。
- **CUDA 被抓到了**：Renovate 會展開 Dockerfile 的 `ARG`，`nvidia/cuda:${CUDA_VERSION}…` 也被當成可升；CUDA 跟主機驅動綁定，關掉。

### 3. 目前過期的套件（2026-10-19 dry-run 結果）

| 套件 | 在哪 | 目前 | 可升到 | 進哪個 PR |
|---|---|---|---|---|
| Gradle | wrapper、`backend/Dockerfile` | 9.2.1 | 9.8.1 | `renovate/gradle` |
| tools.jackson（Jackson 3 BOM） | `build.gradle` | 3.1.7 | 3.2.3 | `renovate/backend-minor-patch` |
| com.fasterxml.jackson（Jackson 2 BOM） | `build.gradle` | 2.22.3 | 2.22.4（未滿 3 天） | 同上 |
| ArchUnit | `build.gradle` | 1.4.1 | 1.5.1 | 同上 |
| @playwright/test | `web/package.json` | 1.63.0 | 1.64.1 | `renovate/web-minor-patch` |
| vue、vite、vitest、vue-tsc、@vitejs/plugin-vue | `web/package.json` | — | 各一個 patch | 同上 |
| Trivy | `ci/vulns.sh` | 0.74.0 | 0.76.0 | 同上 |
| Caddy | `compose.ec2.yaml`、架構頁 | 2.10.0 | 2.11.7（要勾選） | 同上 |
| Alloy | `ops/compose.yaml`、架構頁 | v1.11.0 | v1.20.1 | `renovate/observability-minor-patch` |
| Loki | 同上 | 3.3.0 | 3.7.8 | 同上 |
| Mimir | 同上 | 3.2.1 | 3.2.2 | 同上 |
| Grafana | 同上 | 11.4.0 | 11.6.16；major 13.2.4（要勾選） | 同上；`renovate/major-grafana-monorepo` |
| Terraform aws provider | `infra/` 鎖檔 | 6.68.0 | 6.69.1 | `renovate/terraform-minor-patch` |
| Node | `web/Dockerfile`、`ci/Dockerfile` | 22 | 24（major，要勾選） | `renovate/major-node` |

已是最新：Spring Boot 4.1.1、Spring AI 2.0.1、springdoc 3.1.1、temurin 25、postgres 18.6、Tempo 3.1.0、gitleaks v8.30.1、
所有 GitHub Actions、Terraform 本體、llama.cpp b10964、nginx 1.30（stable 線，見上）。
