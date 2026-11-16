# talkcal app 的映像檔，分兩段：build 用完整 JDK 和 Gradle 編出 jar，runtime 只有 JRE 和那個 jar，
# JDK、Gradle、原始碼都不會進到最後的映像檔。放在 backend/：build 要看得到 src/、build.gradle。

# ════════════════════════════════════════════════════════════════════
# 用裝好 Gradle 的官方映像檔，不用 ./gradlew：wrapper 第一次執行要從 GitHub 下載 Gradle 本體（約 140 MB），
# GitHub 慢的時候要十幾分鐘，Docker Hub 拉映像檔快得多。
# Gradle 版本跟 gradle/wrapper/gradle-wrapper.properties 對齊，本機和容器才用同一版 Gradle。
# JDK 25 跟 build.gradle 的 toolchain 同一版，Gradle 才會直接用映像檔裡的 JDK，不另外下載
FROM gradle:9.2.1-jdk25-noble AS build

ENV GRADLE_USER_HOME=/gradle-cache
WORKDIR /src

# 先只放決定依賴的檔案、把依賴下載好：只改 src/ 的時候這一層用快取，不必重新解析依賴。
# cache mount 讓 Gradle 的下載快取跨 build 保留，又不會進到映像檔
COPY settings.gradle build.gradle ./
RUN --mount=type=cache,target=/gradle-cache \
    gradle dependencies --no-daemon -q > /dev/null

COPY src/ src/
# 只打包、不執行測試：CI 和 ./gradlew deployApp 已經執行過，整合測試要的模型和容器 build 環境裡也沒有
RUN --mount=type=cache,target=/gradle-cache \
    gradle bootJar --no-daemon -q \
    # 另一個 talkcal-plain.jar 只有自己的 class，不能直接執行
    && cp build/libs/talkcal.jar /app.jar

# ════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:25-jre-noble AS runtime

# apt-get upgrade：基底映像檔要等上游重建才帶到修補，這段期間 CI 的漏洞掃描會擋下所有 PR。
# 全部升級、不點名套件，下一個 CVE 才不用再改這裡；代價是 build 結果會隨 Ubuntu 的更新變動。
# curl 給 compose 的 healthcheck 用，JRE 映像檔沒附 curl 也沒附 wget
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app.jar /app/app.jar

# 版本由 compose 的 build.args 傳進來（cd/version.sh 算的），`docker inspect` 看得到執行中的是哪個 commit。
# 放在最後：commit 只改了 web/、docs/，版本也會變，ARG 之後的每一層都要重新 build。放在前面的話，apt-get 那層每次都用不到快取。
# 沒帶參數直接 docker build 時是 local / unknown
ARG APP_VERSION=local
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="talkcal-app" \
      org.opencontainers.image.source="https://github.com/Anderson-chen/talkcal" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$GIT_COMMIT"

# 不用 root 執行：用映像檔內建的 ubuntu 使用者（uid 1000）
USER ubuntu
WORKDIR /app

# 對外發佈成哪一號由 deploy/compose.yaml 的 ports 決定
EXPOSE 8090

# JVM 預設只拿容器記憶體上限的 1/4 當 heap；容器裡只執行這個程式，給到 75%，剩下留給 heap 以外的記憶體。
# 用 exec 形式：java 是 PID 1，docker stop 的 SIGTERM 才會送到 JVM，Spring 才能處理完進行中的請求再停
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
