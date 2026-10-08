# talkcal app 的映像檔。放在 backend/：build 要看得到 src/、build.gradle、gradle wrapper。
# 跟 deploy/llama-server/Dockerfile 同樣是兩段式——
#   build   —— 完整 JDK + Gradle，把程式編成一個可執行的 jar
#   runtime —— 只有 JRE，把 jar 搬過去跑。JDK、Gradle、原始碼都不會跟著上雲端。

# ════════════════════════════════════════════════════════════════════
# 用 Docker Hub 上官方的 Gradle 映像檔，不用 ./gradlew：
# wrapper 第一次執行要下載 Gradle 本體，而 Gradle 的下載點現在轉到 GitHub，本機實測 0.17 MB/s
# （跟 ghcr 同一個慢法），140MB 要十幾分鐘。這個映像檔已經裝好 Gradle，從 Docker Hub 拉很快。
# 版本要跟 gradle/wrapper/gradle-wrapper.properties 對齊（9.2.1）：本機 ./gradlew 和容器 build 用同一版 Gradle。
# JDK 25 跟 build.gradle 的 toolchain 同一版，Gradle 找得到符合的 JDK 就直接用，不會再去下載。
FROM gradle:9.2.1-jdk25-noble AS build

# Gradle 的下載快取（依賴 jar 等）放這裡，下面用 cache mount 掛上
ENV GRADLE_USER_HOME=/gradle-cache
WORKDIR /src

# 先只放「決定依賴是什麼」的檔案，把依賴下載好——這一層的快取只看這兩個檔案。
# 之後只改 src/ 底下的程式，這層不會失效，不必每次重新解析依賴。
COPY settings.gradle build.gradle ./
# --mount=type=cache：Gradle 的下載快取跨 build 保留，但不會進到映像檔裡（映像檔不用背這些）
RUN --mount=type=cache,target=/gradle-cache \
    gradle dependencies --no-daemon -q > /dev/null

COPY src/ src/
# 只打包，不跑測試：測試是 CI（或你本機 ./gradlew build）的事。
# 映像檔 build 要做的是「把已經驗證過的程式碼包起來」，把測試塞進來只會讓每次 build 變慢，
# 而且整合測試需要真的模型伺服器，build 環境裡也沒有。
RUN --mount=type=cache,target=/gradle-cache \
    gradle bootJar --no-daemon -q \
    # Boot 外掛會產出兩個 jar：可執行的 talkcal.jar，和只有自己 class 的 talkcal-plain.jar。要的是前者
    && cp build/libs/talkcal.jar /app.jar

# ════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:25-jre-noble AS runtime

# curl：給 compose 的 healthcheck 打 /actuator/health。JRE 映像檔沒附 curl 也沒附 wget。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app.jar /app/app.jar

# 不跑 root：映像檔內建 uid 1000 的 ubuntu 使用者，直接用（跟 llama-server 那個一樣）
USER ubuntu
WORKDIR /app

# 容器內部的埠。刻意跟本機開發一樣是 8090——埠在容器裡是隔離的，不會跟你本機的 bootRun 撞；
# 對外發佈成哪一號是 compose 的 ports 決定的（deploy/compose.yaml 發佈成 18090）。
EXPOSE 8090

# -XX:MaxRAMPercentage：JVM 預設只拿容器記憶體上限的 1/4 當 heap。容器裡只跑這一支程式，
# 給到 75%，剩下留給 metaspace、執行緒堆疊、direct buffer 這些 heap 以外的記憶體。
# exec 形式（JSON 陣列）：java 直接當 PID 1，docker stop 的 SIGTERM 會送到 JVM，
# Spring 才能優雅關機（處理完手上的請求再停），而不是等 10 秒被 SIGKILL 硬砍。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
