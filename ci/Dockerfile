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
# 官方映像檔裡的 node、npm 就是 web/Dockerfile 打包時用的那一組，不必再信任第三方的套件來源
FROM node:22-bookworm-slim AS node

# 底用 JDK（不是 JRE）：CI 要編譯、跑測試。noble 是 Ubuntu 24.04，跟 app 映像檔同一個發行版
FROM eclipse-temurin:25-jdk-noble

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# Gradle 的家目錄分兩半：
#   wrapper/ —— Gradle 本體（約 140MB）。烤在映像檔裡
#   caches/  —— 下載的 Java 函式庫。run.sh 只把這一個子目錄掛成 volume，蓋不到 wrapper/
# pnpm_config_store_dir：pnpm 的 store（下載過的套件，各專案的 node_modules 從這裡硬連結過去）。
#   pnpm 11 只認 pnpm_config_ 開頭的環境變數，寫成 npm_config_ 會被忽略、store 落在容器裡跟著刪掉
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
# pnpm 也在這一層備好：corepack 照 package.json 的 packageManager 下載那一版（含 sha512 驗證），
# 放在 COREPACK_HOME 烤進映像檔，每次 CI 不必再下載 pnpm 本體；版本也只寫在 package.json 一個地方
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright \
    COREPACK_HOME=/opt/corepack \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /opt/playwright
COPY web/package.json ./
RUN ln -s ../lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack \
    && corepack enable --install-directory /usr/local/bin \
    && corepack install \
    && pnpm dlx "playwright@$(node -p "require('./package.json').devDependencies['@playwright/test']")" install --with-deps chromium

# 程式碼由 run.sh 用 git archive 複製到這裡（只有要測的那個 commit，沒有沒 commit 的檔案）
WORKDIR /work
