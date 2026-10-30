# 原始碼安全靜態分析（SAST）的執行環境：CodeQL CLI＋官方查詢（ci/sast.sh 會 build、啟動它）。
#
# 為什麼不放進 ci/Dockerfile：CodeQL bundle 解開約 2 GB，checks.sh 用不到；
# 放在一起的話，每次升級 CodeQL 都要重 build 整個 CI 映像檔（含 Playwright 的瀏覽器）。
#
# 用 bundle（CLI 加上同一版的查詢包），不是只下載 CLI：查詢跟 CLI 綁在同一個版本，
# 不必在執行時另外從 GitHub 的 registry 拉查詢包，離線也能跑、每次結果都一樣。
#
# 版本：codeql-action 發的 bundle，挑發布滿兩週的（同 web/pnpm-workspace.yaml 的 minimumReleaseAge）。
# --checksum：下載後比對 sha256（GitHub release 頁上的 digest），不一致 build 就失敗 ——
# 同 ci/vulns.sh 釘 Trivy digest 的理由，release 的檔案被換掉也不會被拿來執行
FROM ubuntu:24.04 AS fetch
ADD --checksum=sha256:f002864be6dd8d5d7bdb123aaf7291ec8e291012bd9f70b6d2362f730db52aeb \
    https://github.com/github/codeql-action/releases/download/codeql-bundle-v2.27.2/codeql-bundle-linux64.tar.gz \
    /tmp/codeql.tar.gz
RUN tar -xzf /tmp/codeql.tar.gz -C /opt

# CodeQL 解析 TypeScript（含 .vue 的 <script lang="ts">）要呼叫 node。同 ci/Dockerfile：從官方映像檔搬，版本跟 web/Dockerfile 一樣
FROM node:22-bookworm-slim AS node

# 分兩段：下載的壓縮檔（約 700 MB）留在 fetch 那一段，最後的映像檔只有解開的 /opt/codeql
#
# 底用 JDK 25（跟 ci/Dockerfile、build.gradle 的 toolchain 同一版），不是純 Ubuntu：
# Java 雖然不編譯（build-mode none），CodeQL 還是會跑 ./gradlew 拿依賴清單、下載 jar，才認得出外部函式庫的型別。
# 拿不到時它只能猜，Spring 6.1 的 JdbcClient 就認不出來 —— jdbc.sql("..." + 使用者輸入) 會變成綠燈（#235 實測）
FROM eclipse-temurin:25-jdk-noble
COPY --from=node /usr/local/bin/node /usr/local/bin/node
# jq：ci/sast/analyze.sh 用它讀 SARIF 報告，挑出要擋的結果
RUN apt-get update && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/*
COPY --from=fetch /opt/codeql /opt/codeql
ENV PATH=/opt/codeql:$PATH
WORKDIR /work
