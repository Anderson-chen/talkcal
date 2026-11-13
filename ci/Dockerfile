# CI 的執行環境：ci/run.sh 在這個映像檔裡跑 ci/checks.sh。為什麼要自己的映像檔見 docs/adr/0008-ci-same-script-in-container.md。
#
# 很少變的（JDK、Node、pnpm、Gradle 本體、Playwright 的瀏覽器）烤進映像檔；
# 常變的（Java 函式庫、前端套件）放 ci/run.sh 掛上來的 volume。
# build context 是 ci/run.sh 從要測的 commit 用 git archive 取出的幾個檔案，不是資料夾。
#
# 版本要對齊的地方：
#   JDK 25：backend/build.gradle 的 toolchain、backend/Dockerfile、ci/sast/Dockerfile
#   Node 22：web/Dockerfile、ci/sast/Dockerfile
#   Gradle：backend/gradle/wrapper/gradle-wrapper.properties，由 wrapper 下載，這裡不寫版本

# Node 從官方映像檔整包搬過來：跟 web/Dockerfile 打包用的是同一組 node、corepack，也不必信任另一個套件來源
FROM node:22-bookworm-slim AS node

# ShellCheck 的官方映像檔只有一支靜態連結的執行檔，直接搬。Ubuntu 24.04 的 apt 只有 0.9.0（2022）。
# 版本和 digest 釘死，理由見 ci/vulns.sh
FROM koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d AS shellcheck

# 用 JDK：CI 要編譯。noble（Ubuntu 24.04）跟 app 映像檔同一個發行版
FROM eclipse-temurin:25-jdk-noble

COPY --from=shellcheck /bin/shellcheck /usr/local/bin/shellcheck
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
# 只接 corepack，不接 npm、npx：前端指令都走 pnpm（理由見 web/README.md 的「用 pnpm 不用 npm」）。
# pnpm 實際用哪一版，等下面讀到 package.json 的 packageManager 才決定
RUN ln -s ../lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack \
    && corepack enable --install-directory /usr/local/bin

# Trivy 給 ci/licenses.sh 掃授權。ci/vulns.sh 從這一行讀版本，Renovate 升這一行時兩邊一起換，
# 掃授權和掃漏洞才不會用到不同版本。釘 digest 的理由見 ci/vulns.sh。
# 授權分類內建在執行檔裡，不必下載漏洞資料庫
COPY --from=aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 /usr/local/bin/trivy /usr/local/bin/trivy

# GRADLE_USER_HOME 底下，wrapper/ 是烤進來的 Gradle 本體（約 140 MB），caches/ 是 ci/run.sh 掛的 volume。
# pnpm 的設定只讀 pnpm_config_ 開頭的環境變數，寫成 npm_config_ 不會生效（見 web/README.md）
ENV GRADLE_USER_HOME=/opt/gradle-home \
    pnpm_config_store_dir=/cache/pnpm-store

# 讓 wrapper 照 gradle-wrapper.properties 下載 Gradle 本體、驗 checksum，跟在任何機器上跑 ./gradlew 一樣。
# 這一層只 COPY 這幾個檔案，升級 Gradle 時才會重新下載
WORKDIR /opt/gradle-wrapper
COPY backend/gradlew ./
COPY backend/gradle/wrapper/ gradle/wrapper/
RUN ./gradlew --version --no-daemon

# Playwright 的瀏覽器版本要跟 web/package.json 的 @playwright/test 一模一樣，對不上就報找不到瀏覽器，
# 所以版本直接從 package.json 讀，不另外寫。代價是 package.json 任何一行改了這層都重建（約一兩分鐘）。
# --with-deps 用 apt 裝 Chromium 要的系統函式庫（字型、libnss…），基底映像檔裡沒有。
# corepack install 也在這一層把 pnpm 本體下載進 COREPACK_HOME。
# pnpm dlx 用暫時的 store 再刪掉：預設的 store 位置會被 ci/run.sh 的 volume 蓋住，留著只是占映像檔
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright \
    COREPACK_HOME=/opt/corepack \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /opt/playwright
COPY web/package.json ./
RUN corepack install \
    && pnpm_config_store_dir=/tmp/dlx-store \
       pnpm dlx "playwright@$(node -p "require('./package.json').devDependencies['@playwright/test']")" install --with-deps chromium \
    && rm -rf /tmp/dlx-store

# ci/run.sh 把程式碼 docker cp 到這裡
WORKDIR /work
