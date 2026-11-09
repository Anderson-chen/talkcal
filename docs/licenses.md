# 授權（License）

目的：用到的模型、套件、映像檔都可以合法這樣用；之後有人加了不能用的套件，CI 會擋下來（#53）。

最後核對：2026-10-30

## 結論

| 對象 | 授權 | 商用 | 再散布 | 我們有沒有散布它 |
|---|---|---|---|---|
| **talkcal 本身** | MIT（[LICENSE](../LICENSE)） | 可 | 可 | 原始碼公開在 GitHub |
| Qwen3-8B（本機 llama.cpp 用的 GGUF） | Apache-2.0 | 可 | 可，要附授權全文、保留聲明、標出改了什麼 | 沒有：模型檔不進 git，只在自己的機器和私有 S3 |
| Qwen3 32B（雲端 Bedrock，`qwen.qwen3-32b`） | Apache-2.0＋AWS 的服務條款 | 可 | 不適用：我們只呼叫 API，拿不到權重 | 沒有 |
| llama.cpp（`b10964`） | MIT | 可 | 可，附授權聲明 | 沒有：映像檔自己 build、不推到任何 registry |
| 後端 Java 依賴（runtimeClasspath 222 個，2026-10-30） | Apache-2.0 203 個，其餘 MIT、BSD、EPL、Public Domain（CC0） | 可 | 可 | 沒有：jar 只跑在自己的機器上 |
| 前端打包進去的套件（vue 和它的依賴） | MIT、BSD、ISC | 可 | 可，要附版權和授權聲明 | **有**：js 送到每個使用者的瀏覽器，所以 build 會附上 `licenses.txt` |
| Docker 基底映像檔 | 見[映像檔](#docker-映像檔) | 可 | — | 沒有 |

**沒有 GPL、AGPL 進到會散布或對外提供服務的產物**。觀測工具（Grafana、Loki、Tempo、Mimir）是 AGPL-3.0，但都是原封不動地自己跑，沒有改程式碼、也沒有提供給別人用，不觸發 AGPL 的義務（見[映像檔](#docker-映像檔)）。

## 什麼時候授權的義務會成立

授權條款大多只在**散布**（把程式交給別人）時才有要求，所以先分清楚哪些東西真的離開了我們手上：

| 產物 | 給了誰 | 算不算散布 |
|---|---|---|
| GitHub 上的原始碼 | 所有人 | 算。所以 repo 要有 LICENSE，而且 repo 裡不能放別人不准再散布的東西（模型權重本來就不放） |
| 前端打包出的 js、css | 每個打開網站的使用者 | 算。MIT、BSD 要求附上版權聲明 → `vite build` 產出 `licenses.txt` |
| 後端 jar、Docker 映像檔 | 沒有給任何人，跑在自己的機器上 | 不算。但 **AGPL 例外**：別人透過網路使用改過的 AGPL 程式就要提供原始碼，所以 AGPL 不管在前端還是後端都擋 |
| 模型權重 | 沒有給任何人 | 不算 |

CI 的規則因此是：**前端、後端會打包的依賴都不能是 GPL、AGPL、LGPL，也不能是認不出來的授權**。GPL 在後端（不散布）其實不違法，還是一起擋：哪天要把映像檔給別人、或是改成桌面版，不必回頭清一次；真的需要再個案放行（見[遇到不允許的授權](#遇到不允許的授權怎麼辦)）。

## 模型

### Qwen3-8B（本機，llama.cpp）

- 授權：**Apache-2.0**（[Qwen/Qwen3-8B](https://huggingface.co/Qwen/Qwen3-8B)、官方量化版 [Qwen/Qwen3-8B-GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF) 的 license 欄位都是 `apache-2.0`）
- 檔案：`Qwen3-8B-Q4_K_M.gguf`，官方 GGUF repo 有同名檔案。從第三方（例如社群量化）下載的話，權重的授權照樣承襲 Apache-2.0，但檔案內容要自己確認來源可信（`deploy/compose.yaml` 播種時會比對 `SHA256SUMS`）
- 商用：可以，沒有使用者人數之類的限制
- 為什麼要特別確認：同一家的舊模型不是都這麼寬。Qwen2.5 的 72B、3B 用的是 Qwen License／Qwen Research License，前者月活超過一億要另外申請、後者不能商用。換模型時要重新看一次，不能「同一家就當作一樣」

### Qwen3 32B（雲端，Amazon Bedrock）

- 設定：`infra/envs/1-ec2/variables.tf` 的 `bedrock_model`，預設 `qwen.qwen3-32b`（`bedrock-mantle` 端點的 model ID）
- 誰賣的：AWS 自己（[Serverless Third-Party Models on Amazon Bedrock](https://aws.amazon.com/legal/bedrock/third-party-models/)：Qwen 的 serverless 模型 “sold by AWS”），所以不是跟 Alibaba 另簽約，走的是 AWS 的服務條款和 Acceptable Use Policy
- 模型本身的條款：[Bedrock 的 Qwen3 32B model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-qwen-qwen3-32b.html) 的 “End User License Agreements and Terms of Use” 連到 [Qwen3-32B 的 LICENSE](https://huggingface.co/Qwen/Qwen3-32B/blob/main/LICENSE)，也就是 Apache-2.0
- 商用：可以。我們只拿到模型的回答，回答的使用沒有額外限制
- 要追的不是授權而是**生命週期**：model card 寫 “EOL no sooner than: Apr 29, 2026”，2026-10-19 查的時候狀態還是 Active、EOL 日期 N/A。AWS 宣布下架時要換模型，換的時候照上一節重新看授權

### llama.cpp

- 授權：**MIT**（[LICENSE](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE)，Copyright The ggml authors）
- 我們的用法：`deploy/llama-server/Dockerfile` 下載 `b10964` 的原始碼自己 build。映像檔不推到任何 registry，沒有散布；哪天要推到公開的 registry，映像檔裡要附 llama.cpp 的 LICENSE

## 套件

### 後端：Gradle 的 `checkLicense`

- 工具：[Gradle License Report](https://github.com/jk1/Gradle-License-Report)（`com.github.jk1.dependency-license-report` 3.1.4）
- 範圍：`runtimeClasspath`，也就是 `bootJar` 會打包進去的那些。測試、eval 用的依賴不會上線，不檢查
- 規則：**允許清單** [`backend/allowed-licenses.json`](../backend/allowed-licenses.json)：Apache-2.0、MIT、MIT-0、BSD（2-Clause、3-Clause）、Public Domain、EPL 1.0／2.0。不在清單上的（GPL、AGPL、LGPL、認不出來的）就失敗
  - 只寫「BSD」、沒寫幾條款的（POM 裡的 `The BSD License`、`BSD licence`）不整類放行：可能是有廣告條款的 4-Clause。看過內容的套件（目前是 antlr 的兩個）才用 `moduleName` 個別放行
  - 用允許清單而不是禁止清單：新冒出一個沒看過的授權字串時，預設是擋下來讓人看，而不是預設放行
  - EPL 放行：它的 copyleft 只限檔案本身，原封不動拿來用沒有義務。Tomcat、logback、jakarta.* 都有用
- 雙授權：一個套件列了好幾個授權，**只要其中一個在清單上就過**（外掛的規則）。所以 logback（EPL-2.0 或 LGPL-2.1）、jakarta.annotation-api（EPL-2.0 或 GPL-2.0+CE）是靠 EPL 過的，不是因為放行了 LGPL、GPL
- 什麼時候跑：`./gradlew check`（也就是 `./gradlew build`）、`ci/checks.sh`
- 報告：`backend/build/reports/dependency-license/index.html`（依授權分組的清單），被擋時的明細在同一個資料夾的 `dependencies-without-allowed-license.json`

外掛預設設定的兩個坑，`backend/build.gradle` 都處理了，驗證見[下面](#驗證反例)：

1. **jar 裡附的 LICENSE 檔會被當成整個套件的授權**。itextpdf（AGPL-3.0）的 jar 裡有一份給內嵌元件用的 Apache 授權檔，外掛把它也算成 itextpdf 的授權，再加上「其中一個在清單上就過」，AGPL 的套件就這樣過了。改成只看套件自己宣告的授權（POM、jar 的 manifest），不看 jar 裡附帶的授權檔（`DeclaredLicensesOnly` 這個 filter）
2. **加了依賴，`checkLicense` 還是 UP-TO-DATE**。報告重新產生了，檢查卻沿用上一次「通過」的結果。改成每次都重新跑（只要幾秒）

### 前端：`ci/licenses.sh`（Trivy）

- 工具：Trivy（`ci/vulns.sh` 掃漏洞已經在用，同一版、同一個 digest），`--scanners license`
- 範圍：`dependencies`（和它們的依賴），也就是可能被打包進 `dist/` 的。devDependencies（vite、vitest、playwright…）只在 build、測試時用，不散布
- 怎麼掃：另外裝一份只有 `dependencies` 的 node_modules，再讓 Trivy 讀裡面每個套件的 `package.json`
- 規則：Trivy 依 SPDX 把授權分類、給嚴重度，擋 CRITICAL（forbidden：AGPL、SSPL、CC-BY-NC…）、HIGH（restricted：GPL、LGPL…）、UNKNOWN（認不出來的）。另外擋兩種 Trivy 不會報的情況：**一個套件都沒掃到**、**套件沒寫授權**
- 什麼時候跑：`ci/checks.sh`（CI 映像檔裡有 Trivy 的執行檔）
- 散布時附聲明：`vite build` 的 `build.license` 產出 `dist/licenses.txt`，列出實際打包進去的套件（tree-shaking 之後只剩 4 個 `@vue/*`）的授權全文，跟網站一起送出去，網址 `/licenses.txt`

為什麼不直接掃 lockfile：npm 的 `package-lock.json` 有記每個套件的授權，Trivy 讀得到；但前端換成 pnpm（#157）之後的 `pnpm-lock.yaml` 沒有記。2026-10-19 實測，Trivy 找到 24 個套件、授權一個都沒有，**檢查會靜靜地通過**。所以 `ci/licenses.sh` 用 `pnpm install --prod` 另外裝一份，讀 node_modules 裡每個套件自己的 package.json。套件從 pnpm store 拿（CI 前一步已經下載過），不會再連網下載。

### 為什麼後端不也用 Trivy

#15 已經用 Trivy 掃映像檔，本來想一套工具掃到底。實測 `trivy image --scanners license` 掃 app 映像檔，jar 裡的 Java 函式庫**一個授權都沒有回報**（Trivy 讀 jar 不會去找 POM 裡的授權），結果是「沒有問題」—— 跟上面 pnpm 的情況一樣，是靜靜地通過。所以 Java 交給讀得到 POM 的 Gradle 外掛。

## Docker 映像檔

映像檔都是自己 build、只在自己的機器（本機、EC2）上跑，不推到任何 registry，所以沒有散布。基底映像檔裡的作業系統套件（Ubuntu、Alpine 的 bash、coreutils、busybox 是 GPL）因此不影響；哪天要公開映像檔，要附上這些套件的原始碼取得方式。

| 映像檔 | 用在哪 | 主要授權 | 備註 |
|---|---|---|---|
| `eclipse-temurin:25-jre-noble` | app 執行 | GPL-2.0 + Classpath Exception（OpenJDK） | Classpath Exception：在 JVM 上跑的程式不受 GPL 影響 |
| `gradle:9.2.1-jdk25-noble`、`eclipse-temurin:25-jdk-noble` | build、CI | Apache-2.0（Gradle）、同上 | 只在 build 階段，不進最後的映像檔 |
| `nginx:1.30-alpine` | 前端 | BSD-2-Clause | |
| `node:22-alpine`、`node:22-bookworm-slim` | build、CI | MIT | 只在 build 階段 |
| `postgres:18.6-alpine` | 資料庫 | PostgreSQL License（類似 MIT） | |
| `nvidia/cuda:13.0.1-{devel,runtime}-ubuntu24.04` | llama-server | NVIDIA Deep Learning Container License／CUDA EULA | runtime 函式庫 NVIDIA 允許隨應用程式一起散布；目前也沒有散布 |
| `caddy:2.10.0-alpine` | EC2 的 HTTPS | Apache-2.0 | |
| `grafana/alloy` | 觀測：收集 | Apache-2.0 | |
| `grafana/grafana`、`loki`、`tempo`、`mimir` | 觀測：儲存、看板 | **AGPL-3.0** | 原封不動自己用，沒改程式、沒提供給別人當服務，不觸發 AGPL。要改它們的程式碼、或開放給外部使用者時，要公開改過的原始碼 |
| `aquasec/trivy` | CI 掃描 | Apache-2.0 | |

## 其他程式與工具

Java、JavaScript 套件以外，還有這些會被執行或跟著 repo 走的東西。都是「拿來用、沒有散布」或寬鬆授權，所以不放進 CI 的檢查，記在這裡：

| 對象 | 授權 | 我們怎麼用 | 結論 |
|---|---|---|---|
| 映像檔裡的作業系統套件（apt、apk 裝的 curl、libgomp1、busybox…） | 大多是 GPL、LGPL | 跑在自己的機器上 | 不散布就沒有義務（見上一節）。目前沒有逐一列出的清單 |
| llama.cpp 內附的 C／C++ 函式庫（`vendor/`：cpp-httplib、nlohmann/json、miniaudio、stb、subprocess.h、xxhash…） | MIT、BSD、Public Domain、Unlicense | 跟 llama.cpp 一起編進 llama-server | 都是寬鬆授權；映像檔要公開時跟 llama.cpp 的 LICENSE 一起附上 |
| CUDA 函式庫 | NVIDIA EULA | llama-server 的 runtime | NVIDIA 允許隨應用程式散布 runtime 函式庫 |
| Terraform CLI（2026-10-30 本機 v1.15.8） | **BUSL-1.1**（1.6 起從 MPL-2.0 改過來） | 管理自己的 AWS | 可以。BUSL 限制的是拿它做跟 HashiCorp 競爭的產品；不想受這個限制的替代品是 OpenTofu（MPL-2.0） |
| Terraform provider（`hashicorp/aws`、`hashicorp/random`） | MPL-2.0 | 同上 | 可以 |
| k6（`perf/` 壓測） | **AGPL-3.0** | 本機原封不動執行 | 可以。沒有修改、也沒有提供給別人當服務 |
| squawk（`ci/migrations.sh`） | Apache-2.0 | CI 工具 | 可以 |
| gitleaks（`ci/secrets.sh`） | MIT | CI 工具 | 可以 |
| GitHub Actions（`actions/*`、`gradle/actions`） | MIT | CI | 可以 |
| Docker Engine、AWS CLI（EC2 的 user data 安裝） | Apache-2.0 | EC2 上執行 | 可以 |
| Google Fonts：Manrope、Noto Sans TC（`web/index.html`） | SIL OFL 1.1 | 從 Google 的 CDN 載入，沒有放進 repo | 可以。授權之外要注意的是隱私：使用者的 IP 會送到 Google（#52 的範圍） |
| `backend/gradle/wrapper/gradle-wrapper.jar`（在 git 裡） | Apache-2.0 | 跟著 repo 散布 | 可以，Gradle 官方就是這樣發的 |

## 授權會變：什麼時候要重新確認

授權不是查一次就永遠成立，近年改過的不少，而且常常是從寬鬆改成有限制：

- Terraform：2023 年 MPL-2.0 → BUSL-1.1（上表就是）
- Grafana、Loki、Tempo：2021 年 Apache-2.0 → AGPL-3.0
- Elasticsearch：2021 年 Apache-2.0 → SSPL／Elastic License，2024 年再加回 AGPL 選項
- Redis：2024 年 BSD → RSAL／SSPL，2025 年再加回 AGPL 選項
- 模型：同一家的不同版本、不同尺寸授權就可能不同（見 [Qwen3-8B](#qwen3-8b本機llamacpp)）

要抓的是兩件事：**我們升到的新版本換了授權**，和**現在用的服務條款被改了**。

| 對象 | 授權綁在哪裡 | 怎麼發現它變了 |
|---|---|---|
| Java、npm 套件 | 每一個版本各自宣告 | **自動**。升版一定會改 `build.gradle` 或 lockfile，CI 的 `checkLicense`、`ci/licenses.sh` 就會用新版本宣告的授權重新判斷；Renovate 開的升版 PR 也一樣（docs/dependency-updates.md） |
| 模型權重（Qwen3-8B） | 下載的那一份檔案 | 換模型、換尺寸、換量化來源時，照[模型](#模型)一節重看。原本那份檔案的授權不會因為上游改了而失效 |
| Bedrock 上的模型 | AWS 的服務條款、model card 的 EULA 連結 | AWS 可以改條款，我們不會收到 PR。換模型時、以及每次核對（見下面）時重看 model card |
| Docker 基底映像檔 | 版本 tag | 升大版本（換 tag）時重看上面的表；同一個 tag 重新 build 拿到的是修補，授權不會換 |
| 工具（Terraform、k6、squawk、gitleaks…） | 安裝的那一版 | 升大版本時重看；改了授權通常會寫在 release note、專案公告 |

### 如果哪一天 Spring 開始收費

分成兩種情況，影響差很多：

1. **新版本換成收費授權**：已經發布的版本收不回去。Apache-2.0 第 2 條給的授權是 “perpetual, worldwide, … irrevocable”，Spring Boot 4.1.1 拿到的授權永遠有效。新版本換授權的話，升版那個 PR 的 `checkLicense` 會紅燈（新的授權不在允許清單上），不會不知不覺換過去。
2. **免費的維護期結束**：這個比較可能，而且**已經發生了**。[Spring 的支援政策](https://spring.io/support-policy)：每個小版本（例如 4.1.x）開源的免費修補至少 13 個月；之後的修補、以及「Day 0」安全修補，要買 Broadcom 的 Tanzu Spring 訂閱才拿得到。授權沒變，但停在舊版本就收不到漏洞修補了。

對策：

- **跟著升版，不要停在舊的小版本**：#48 的依賴升級負責這件事。停太久 `ci/vulns.sh` 會開始報修不了的漏洞，那就是提醒
- **選有社群接手的東西**：前例都是社群 fork 出免費版本，例如 Oracle JDK 2019 年起商用要付費 → 我們用的是 Eclipse Temurin（OpenJDK，GPL-2.0+CE，免費）；Terraform 改 BUSL → OpenTofu；Redis 改授權 → Valkey
- 真的發生時，選項依序是：升到還在免費維護期的版本 → 換社群版本 → 評估付費

所以每一列都附了「看的時候是哪一版」，重看時才知道要比對什麼。沒有自動通知的那幾類，在升級時順手看，並把開頭的「最後核對」日期往前推；很久沒升級的話，**至少每半年**整份看一次（2027-04 前）。

## talkcal 本身的授權：MIT

公開的 repo 沒有 LICENSE，法律上等於保留所有權利：別人看得到，卻不能合法地複製、改、拿去用。作品集要的是「看得到、也拿得走」，所以要加。

在 MIT 和 Apache-2.0 之間選了 **MIT**：

- 最短、最多人認得：面試官一眼知道是什麼，不用讀
- Apache-2.0 多出來的是專利授權條款和「改了什麼要註明」、NOTICE 檔的要求。專利條款對有專利的公司才有意義，這個 repo 沒有；多出來的要求反而讓想參考的人多一層手續
- 相容性沒有差別：我們用的 Apache-2.0 套件（Spring、Qwen3）沒有被複製進 repo，只是依賴；MIT 的專案依賴 Apache-2.0 的套件沒有問題

## 遇到不允許的授權怎麼辦

CI 紅在授權時，依序考慮：

1. **換一個套件**：通常有同功能、授權寬鬆的替代品
2. **看有沒有雙授權、是不是寫法不同**：很多套件「GPL 或商業授權」「LGPL 或 EPL」；也常見同一個授權換個寫法（「BSD licence」）。確認內容之後，在 `backend/allowed-licenses.json` 加一條寫明 `moduleName` 的規則（例如 antlr 的兩條 BSD）
3. **真的要用**：確認在我們的用法下沒有義務（例如只在後端、不散布的 GPL），才放行，而且只放行那一個套件 ——
   - 後端：在 `backend/allowed-licenses.json` 加一條同時寫 `moduleName` 和 `moduleLicense` 的規則，不要只寫 `moduleLicense`（那等於整個授權類別都開了）
   - 前端：前端的套件都會散布，GPL、AGPL 不放行
   - 放行的理由寫進這份文件

## 驗證（反例）

2026-10-19 在本機實測，每一項都做完就還原：

| 情況 | 期待 | 結果 |
|---|---|---|
| 後端現況（2026-10-19：204 個；合併 master 之後 2026-10-30：222 個） | 過 | 兩次都過（`dependenciesWithoutAllowedLicenses` 是空的） |
| 後端加 `com.itextpdf:itextpdf:5.5.13.4`（AGPL-3.0） | 擋 | **修正前：過**（被 jar 裡的 Apache 授權檔帶過）。加了 `DeclaredLicensesOnly` 之後：擋，`[GNU Affero General Public License v3]` |
| 後端加 `com.mysql:mysql-connector-j`（GPL-2.0 + Universal FOSS Exception） | 擋 | 修正前：過（同上）；修正後：擋 |
| 後端加 `org.mariadb.jdbc:mariadb-java-client`（只有 LGPL-2.1），不帶 `--rerun` | 擋 | 擋，`[LGPL-2.1-or-later]`（確認 UP-TO-DATE 的坑修好了） |
| 後端雙授權：logback（EPL／LGPL）、jakarta.annotation-api（EPL／GPL+CE） | 過 | 過（靠 EPL） |
| 前端現況（還是 npm 時，`dependencies` 24 個，含 vue 的 peer dependency typescript） | 過 | 過：MIT ×20、BSD-2-Clause、BSD-3-Clause、ISC、Apache-2.0 |
| 前端加 `chessground`（GPL-3.0-or-later）到 `dependencies` | 擋 | 擋：`chessground：GPL-3.0-or-later（restricted，HIGH）` |
| 同一個套件加到 `devDependencies` | 過（不會打包） | 過 |
| 前端加 `elkjs`（`EPL-2.0 OR GPL-3.0-or-later`） | 過（雙授權，跟後端同一個規則） | 過 |
| 前端用 #157 的 `pnpm-lock.yaml`（pnpm 11.28.4） | 過，而且真的有掃到授權 | 過，23 個套件都有授權 |
| 同上再 `pnpm add chessground` | 擋 | 擋 |
| `dependencies` 是空的（模擬 node_modules 沒裝起來） | 擋 | 擋：「一個套件都沒掃到」 |
| node_modules 裡有個 package.json 沒寫 `license` 的套件 | 擋 | Trivy 本身：列出套件、**不報任何問題**；`ci/licenses.sh` 的判斷：擋，「package.json 沒有寫授權」 |
| `trivy fs` 直接掃 `pnpm-lock.yaml`（沒採用的做法） | — | 24 個套件、0 個授權 → 會靜靜地通過，所以不採用 |
| `trivy image` 掃 app 映像檔的 jar（沒採用的做法） | — | 0 個授權 → 會靜靜地通過，所以後端改用 Gradle 外掛 |

## 參考

- [Qwen/Qwen3-8B-GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF)、[Qwen/Qwen3-32B LICENSE](https://huggingface.co/Qwen/Qwen3-32B/blob/main/LICENSE)
- [Amazon Bedrock：Qwen3 32B model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-qwen-qwen3-32b.html)、[Serverless Third-Party Models on Amazon Bedrock](https://aws.amazon.com/legal/bedrock/third-party-models/)
- [llama.cpp LICENSE](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE)
- [Gradle License Report](https://github.com/jk1/Gradle-License-Report)、[Trivy：License Scanning](https://trivy.dev/latest/docs/scanner/license/)、[Vite：build.license](https://vite.dev/config/build-options#build-license)
