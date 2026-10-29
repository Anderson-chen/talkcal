# 0016. 套件升級交給 Renovate（Mend 的 GitHub App），不用 Dependabot

- 狀態：已採用
- 日期：2026-10-19
- 相關：issue [#48](https://github.com/Anderson-chen/talkcal/issues/48)、epic [#46](https://github.com/Anderson-chen/talkcal/issues/46)、[#107](https://github.com/Anderson-chen/talkcal/issues/107)（前端換 pnpm）、[0013](0013-ci-same-script-in-container.md)

## 背景

repo 裡沒有任何自動升級的機制，版本散在七種地方：

| 在哪 | 例子 |
|---|---|
| Gradle | `build.gradle` 的 Boot 外掛、Spring AI BOM、為了安全修補蓋過 BOM 的 Jackson / Tomcat；`gradle-wrapper.properties` |
| 前端 | `web/package.json`（#107 合併後鎖檔換成 `pnpm-lock.yaml`，pnpm 版本寫在 `packageManager`，是 **pnpm 11**） |
| Dockerfile | `backend/`、`web/`、`ci/` 的 `FROM`；`deploy/llama-server/Dockerfile` 的 `ARG LLAMA_BUILD` |
| compose | `deploy/`、`ops/`、`infra/envs/1-ec2/` 的 `image:` |
| GitHub Actions | `.github/workflows/*.yml` 的 `uses:` |
| Shell 腳本 | `ci/vulns.sh`（Trivy）、`ci/secrets.sh`（gitleaks）的 `image=名稱:版本@sha256:…` |
| 文件 | `docs/architecture/index.html` 把 compose 的映像檔版本抄了一份 |

CI 每天掃漏洞（`ci/vulns.sh`），掃到了也只會紅燈，還是要人去找新版、改版號。
一直沒升，等到非升不可（例如 #85 的 Tomcat、Jackson 漏洞）時跨了好幾版，一次要處理的變動就很大。

## 選項

2026-10-19 查證：

| | Dependabot | Renovate |
|---|---|---|
| Gradle、GitHub Actions、Dockerfile、compose | 都支援（`docker-compose` 是獨立的 ecosystem） | 都支援 |
| pnpm | 跟 npm 共用 `npm` ecosystem，**官方表格只寫到 pnpm v7–v10**；#107 用的是 pnpm 11 | 讀 `packageManager` 決定用哪版 pnpm 更新鎖檔，野外已有 Renovate 開的 pnpm 11.x PR |
| Shell 腳本、`ARG`、HTML 裡的版本 | 沒有自訂規則，看不到 | `customManagers` 用 regex 指定 |
| 映像檔的安全修補 PR | `docker`、`docker-compose` **不支援 security updates** | 依版本比對 OSV 資料庫，有修補就立刻開，不等排程 |
| 分組 | `groups` 可依 pattern、更新類型分組 | `packageRules` 可依管理器、資料來源、更新類型、套件名稱任意組合，跨檔案綁在一起升 |
| major 怎麼處理 | 照排程開，或用 `ignore` 擋掉 | `dependencyDashboardApproval`：列在 Dashboard 上，勾了才開 |
| 安裝 | GitHub 內建，只要一個 `.github/dependabot.yml` | 要裝 Mend 的 Renovate App（免費），或自己用 Actions 跑（要另外管 token） |
| 授權 | GitHub 的服務 | AGPL-3.0 開源；用 App 的話程式碼由 Mend 的服務讀取 |

來源：[GitHub Docs：Dependabot supported ecosystems](https://docs.github.com/en/code-security/dependabot/ecosystems-supported-by-dependabot/supported-ecosystems-and-repositories)、
[Renovate Docs：Managers](https://docs.renovatebot.com/modules/manager/)（對應 Renovate 44.167.5）、
[appsecsanta：Dependabot vs Renovate](https://appsecsanta.com/dependabot-vs-renovate)、
[一份 2026-03 的 ADR：Renovate over Dependabot](https://codex.devs.constructions-3d.com/decisions/2026-03-19-renovate-over-dependabot)。

## 決定

**用 Renovate，透過 Mend 的 Renovate GitHub App 跑；設定在 [`.github/renovate.json5`](../../.github/renovate.json5)。**

決定性的是兩件事：

1. **pnpm 11**：#107 合併之後前端就是 pnpm 11，Dependabot 的官方支援表還停在 v10。選一個「現在支援、明天可能壞」的工具不划算。
2. **看得到全部七種地方**：掃描工具（Trivy、gitleaks）本身舊了，CI 的兩道關卡就跟著變弱；Dependabot 看不到 shell 腳本裡的版本。
   架構頁抄的版本也用同一招，跟 compose 在同一個 PR 裡改，不會一邊升了一邊沒改。

設定的取捨（細節寫在設定檔註解）：

- **排程**：週一早上台灣時間 9 點前開；新版滿 14 天才開，跟 repo 選套件的兩週規則、`web/pnpm-workspace.yaml` 的 `minimumReleaseAge`（#107）一致 —— 比 pnpm 短的話，CI 的 `pnpm install --frozen-lockfile` 會擋下 Renovate 的 PR。
- **分組**：minor、patch 依生態系各一個 PR（後端、前端、映像檔、Actions、Terraform）；要一起升的綁在一起（Gradle wrapper ＋ gradle 映像檔、各處的 JDK、各處的 Node、觀測堆疊）。
- **安全修補**：不等週一的排程，但一樣滿 14 天才開。repo 沒開 GitHub 的 Dependabot alerts，所以讓 Renovate 自己查 OSV。
  HIGH、CRITICAL 要提早由人在 Dependency Dashboard 勾選：Renovate 的設定做不到依嚴重度分流（實測見 [docs/dependency-updates.md](../dependency-updates.md)）。
- **major**：只列在 Dependency Dashboard，勾了才開 PR。GitHub Actions 例外（major 多半只是換 Node 執行環境）。
- **不自動合併**：每個 PR 都由使用者看過、CI 綠燈才合（[docs/dependency-updates.md](../dependency-updates.md)）。
- **用 App 不自己跑**：自己用 Actions 跑要一把能開 PR、能觸發 CI 的 token（PAT 或自建 GitHub App），還要自己升 Renovate 本身；App 這些都不用管。

## 代價

- 多一個第三方服務能讀寫這個 repo（Mend 的 App 要 contents、pull requests、issues 的寫入權限）。
- 設定比 Dependabot 複雜，寫錯不一定會被 validator 擋下來（實測：拼錯欄位、型別錯會擋；列舉值打錯、regex 少了捕捉群組不會擋），所以改設定要跑本機 dry-run 看實際抓到什麼。
- `pr-issue` 檢查要放過 Renovate 的 PR（它們沒有對應的 issue），追蹤改看 Dependency Dashboard。
- Docker Hub 不登入翻不到舊 tag 的發佈時間，映像檔設成「拿不到時間就不等」，所以沒有日期的映像檔版本不受 14 天保護（要補的話給 Renovate 一把 Docker Hub 唯讀 token）。
- 所有更新都晚 14 天；HIGH、CRITICAL 要提早得有人看 Dashboard、勾選，前端還要手動在 `pnpm-workspace.yaml` 放行那一版。

## 什麼時候重想

- Dependabot 支援 pnpm 11，而且 shell 腳本裡的版本改成別的方式釘（例如全部搬進 Dockerfile） → Dependabot 的「零安裝」就值得了。
- repo 搬離 GitHub → Renovate 支援 GitLab、Bitbucket 等，設定可以照搬；Dependabot 不行。
- 要自動合併 patch → 先有足夠的測試覆蓋，再對 devDependencies 的 patch 開 `automerge`。
- 架構頁的版本靠 regex 從 HTML 裡抓，頁面格式一改就默默抓不到 → 改成頁面從 compose 讀版本，或在 CI 比對兩邊，拿掉這兩個 regex。
- Mend 的 App 改成收費或限制公開 repo → 改用 `renovatebot/github-action` 自己跑，同一份設定。

## 證據

- [`.github/renovate.json5`](../../.github/renovate.json5)：設定，每條規則都寫了理由
- [docs/dependency-updates.md](../dependency-updates.md)：審查、合併、major 的流程，validator 的反例和 dry-run 抓到的過期套件
- [`.github/workflows/pr-issue.yml`](../../.github/workflows/pr-issue.yml)：放過 `renovate[bot]` 的 PR
