# CodeQL 的執行環境（ci/sast.sh build、啟動，ci/sast/analyze.sh 在裡面跑）。
#
# 不放進 ci/Dockerfile：CodeQL bundle 解開約 2 GB，checks.sh 用不到；
# 放在一起的話，每次升級 CodeQL 都要重 build 整個 CI 映像檔。
#
# 用 bundle（CLI 加同一版的查詢包）：執行時不必另外從 GitHub 拉查詢包，離線也能跑，結果固定。
# 版本挑發布滿兩週的（理由見 .github/renovate.json5 的 minimumReleaseAge）。
# --checksum 比對 release 頁上的 sha256，不一致 build 就失敗：release 的檔案被換掉也不會被執行
FROM ubuntu:24.04 AS fetch
ADD --checksum=sha256:f002864be6dd8d5d7bdb123aaf7291ec8e291012bd9f70b6d2362f730db52aeb \
    https://github.com/github/codeql-action/releases/download/codeql-bundle-v2.27.2/codeql-bundle-linux64.tar.gz \
    /tmp/codeql.tar.gz
# 只留 java、javascript、actions，和它們解析設定檔要的 html、xml、yaml、properties，映像檔小一半
RUN tar -xzf /tmp/codeql.tar.gz -C /opt \
    && cd /opt/codeql \
    && rm -rf cpp csharp go python ruby rust swift \
              qlpacks/codeql/cpp-* qlpacks/codeql/csharp-* qlpacks/codeql/go-* qlpacks/codeql/python-* \
              qlpacks/codeql/ruby-* qlpacks/codeql/rust-* qlpacks/codeql/swift-* qlpacks/codeql/*-examples

# CodeQL 解析 TypeScript（含 .vue 的 <script lang="ts">）要 node。從官方映像檔搬，版本跟 web/Dockerfile 一樣
FROM node:22-bookworm-slim AS node

# 下載的壓縮檔（約 700 MB）留在 fetch 那一段，這一段只拿解開的 /opt/codeql。
# 用 JDK：Java 雖然不編譯，CodeQL 還是會跑 ./gradlew 下載 jar，才認得外部函式庫的型別。
# 拿不到時它只能猜，Spring 的 JdbcClient 就認不出來，jdbc.sql("..." + 使用者輸入) 會是綠燈
FROM eclipse-temurin:25-jdk-noble
COPY --from=node /usr/local/bin/node /usr/local/bin/node
# jq：ci/sast/analyze.sh 用它讀 SARIF 報告
RUN apt-get update && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/*
COPY --from=fetch /opt/codeql /opt/codeql
ENV PATH=/opt/codeql:$PATH
WORKDIR /work
