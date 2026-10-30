# CI 的執行環境：本機（ci/run.sh、pre-push hook）和 GitHub Actions 都在這個映像檔裡跑檢查。
#
# 為什麼要自己的映像檔，而不是直接用機器上裝好的工具：
# 「本機綠、CI 紅」最常見的原因是兩邊環境不一樣（JDK、Node 版本、個人設定、殘留的 build/）。
# 兩邊都在同一個映像檔裡跑，版本只定義在這裡一次，綠燈的意思就一致了。
#
# 什麼烤進映像檔、什麼放快取 —— 看它多久變一次：
#   JDK、Node、Gradle 本體 —— 很少變，烤進來。改版時映像檔自動重 build（見下面 Gradle 那段）
#   pnpm 本體 —— 跟著 web/package.json 的 packageManager，很少變，烤進來
#   Java 函式庫、前端套件 —— 跟著 build.gradle、pnpm-lock.yaml 常常變，放 run.sh 掛上來的 volume（pnpm store）
#
# build context 不是資料夾，是 run.sh 用 git archive 從「要測的那個 commit」取出的
# ci/Dockerfile、backend/gradlew、backend/gradle/wrapper/ —— 跟程式碼一樣，不讀工作目錄裡沒 commit 的東西。
#
# 版本對齊：
#   JDK 25  —— build.gradle 的 toolchain，以及 app 映像檔（backend/Dockerfile）的 temurin:25
#   Node 22 —— web/Dockerfile 的 node:22
#   Gradle  —— backend/gradle/wrapper/gradle-wrapper.properties（由 wrapper 自己下載，不另外寫版本）

# Node 從官方映像檔整包搬過來，不在 Ubuntu 上另外裝：
# 官方映像檔裡的 node、corepack 就是 web/Dockerfile 打包時用的那一組，不必再信任第三方的套件來源
FROM node:22-bookworm-slim AS node

# ShellCheck（checks.sh 檢查 repo 裡的 shell 腳本）：官方映像檔裡只有一支靜態連結的執行檔，直接搬過來。
# 不用 apt 裝：Ubuntu 24.04 的是 0.9.0（2022），官方映像檔的版本＋digest 釘死，跟 ci/vulns.sh 的 Trivy 同一個做法
FROM koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d AS shellcheck

# 底用 JDK（不是 JRE）：CI 要編譯、跑測試。noble 是 Ubuntu 24.04，跟 app 映像檔同一個發行版
FROM eclipse-temurin:25-jdk-noble

COPY --from=shellcheck /bin/shellcheck /usr/local/bin/shellcheck
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
# 只接 corepack，不接 npm、npx：容器裡的前端指令都走 pnpm（為什麼見 web/README.md 的「用 pnpm 不用 npm」）。
# corepack enable 建出 pnpm 這個指令，實際用哪一版等下面讀到 package.json 才決定
RUN ln -s ../lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack \
    && corepack enable --install-directory /usr/local/bin

# Gradle 的家目錄分兩半：
#   wrapper/ —— Gradle 本體（約 140MB）。烤在映像檔裡
#   caches/  —— 下載的 Java 函式庫。run.sh 只把這一個子目錄掛成 volume，蓋不到 wrapper/
# pnpm_config_store_dir：pnpm 的 store（下載過的套件），run.sh 掛 volume 上來。前綴不能寫成 npm_config_（見 web/README.md）
ENV GRADLE_USER_HOME=/opt/gradle-home \
    pnpm_config_store_dir=/cache/pnpm-store

# 讓 wrapper 自己把 Gradle 本體下載好：下載哪一版、從哪裡下載、驗證 checksum，
# 都照 gradle-wrapper.properties 來，跟在任何機器上跑 ./gradlew 一模一樣。
# 這一層的快取只看這三個檔案：升級 Gradle（改 properties）時才會重新下載，平常改程式碼不受影響
WORKDIR /opt/gradle-wrapper
COPY backend/gradlew ./
COPY backend/gradle/wrapper/ gradle/wrapper/
RUN ./gradlew --version --no-daemon

# Playwright 的瀏覽器（前端 E2E，web/e2e/）。版本要跟 web/package.json 的 @playwright/test 一模一樣：
# 每一版 Playwright 只認得自己那一版的瀏覽器，對不上就直接報「找不到瀏覽器」。
# 所以版本不在這裡另外寫一次，直接從 package.json 讀 —— 升級 Playwright 時這一層自動重建，不會忘了改。
# 代價：package.json 任何一行改了，這層都會重建（重新下載瀏覽器，約一兩分鐘）；它不常改，划得來。
# --with-deps：順便用 apt 裝 Chromium 需要的系統函式庫（字型、libnss 等），Ubuntu 基底映像檔裡沒有。
# 瀏覽器放在固定位置、烤進映像檔：跟 Gradle 本體同一個道理，很少變，不必每次 CI 都下載
#
# pnpm 本體也在這一層下載（corepack install 讀 packageManager），烤在 COREPACK_HOME，每次 CI 不必再下載。
# pnpm dlx 下載的 playwright 套件放暫時的 store、用完刪掉：預設的 store 位置會被 run.sh 的 volume 蓋住，留著只是占映像檔
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright \
    COREPACK_HOME=/opt/corepack \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /opt/playwright
COPY web/package.json ./
RUN corepack install \
    && pnpm_config_store_dir=/tmp/dlx-store \
       pnpm dlx "playwright@$(node -p "require('./package.json').devDependencies['@playwright/test']")" install --with-deps chromium \
    && rm -rf /tmp/dlx-store

# 程式碼由 run.sh 用 git archive 複製到這裡（只有要測的那個 commit，沒有沒 commit 的檔案）
WORKDIR /work
