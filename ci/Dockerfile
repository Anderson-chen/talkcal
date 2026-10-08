# CI 的執行環境：本機（ci/run.sh、pre-push hook）和 GitHub Actions 都在這個映像檔裡跑檢查。
#
# 為什麼要自己的映像檔，而不是直接用機器上裝好的工具：
# 「本機綠、CI 紅」最常見的原因是兩邊環境不一樣（JDK、Node 版本、個人設定、殘留的 build/）。
# 兩邊都在同一個映像檔裡跑，版本只定義在這裡一次，綠燈的意思就一致了。
#
# 什麼烤進映像檔、什麼放快取 —— 看它多久變一次：
#   JDK、Node、Gradle 本體 —— 很少變，烤進來。改版時映像檔自動重 build（見下面 Gradle 那段）
#   Java 函式庫、npm 套件 —— 跟著 build.gradle、package-lock.json 常常變，放 run.sh 掛上來的 volume
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
ENV GRADLE_USER_HOME=/opt/gradle-home \
    npm_config_cache=/cache/npm

# 讓 wrapper 自己把 Gradle 本體下載好：下載哪一版、從哪裡下載、驗證 checksum，
# 都照 gradle-wrapper.properties 來，跟在任何機器上跑 ./gradlew 一模一樣。
# 這一層的快取只看這三個檔案：升級 Gradle（改 properties）時才會重新下載，平常改程式碼不受影響
WORKDIR /opt/gradle-wrapper
COPY backend/gradlew ./
COPY backend/gradle/wrapper/ gradle/wrapper/
RUN ./gradlew --version --no-daemon

# 程式碼由 run.sh 用 git archive 複製到這裡（只有要測的那個 commit，沒有沒 commit 的檔案）
WORKDIR /work
