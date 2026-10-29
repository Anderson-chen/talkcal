# talkcal 前端的映像檔。跟 app 的 Dockerfile（backend/Dockerfile）一樣是兩段式——
#   build   —— Node + pnpm，把 .vue / .ts 編成靜態檔（dist/）
#   runtime —— 只有 nginx，送靜態檔、把 /api 轉給 app。Node、node_modules、原始碼都不會跟著上雲端。

# ════════════════════════════════════════════════════════════════════
# 跟本機開發同一個大版本（node -v 是 22）
FROM node:22-alpine AS build
WORKDIR /src

# pnpm 不另外裝，由 Node 附的 corepack 照 package.json 的 packageManager 下載（見 README.md 的「用 pnpm 不用 npm」）。
# 不關掉提示的話，corepack 第一次下載前會停下來問 Y/n
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable

# 先只放「決定依賴是什麼」的檔案——只改 src/ 時這層快取不失效，不必每次重裝依賴。
# --frozen-lockfile：嚴格照 pnpm-lock.yaml 裝，lock 跟 package.json 對不上就直接失敗，
# 不會偷偷升級（TypeScript 釘在 5.9 的理由見 web/README.md）。
# store 和 corepack 下載的 pnpm 本體都放 BuildKit 的快取：依賴改了重裝時不必重新下載
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm-store --mount=type=cache,target=/root/.cache/node/corepack \
    pnpm_config_store_dir=/pnpm-store pnpm install --frozen-lockfile

COPY . .
# build 會先跑 vue-tsc 型別檢查：型別錯就 build 失敗，壞的前端進不了映像檔
RUN pnpm run build

# ════════════════════════════════════════════════════════════════════
# 1.30 是 nginx 的 stable 線，只收安全和錯誤修補；原本的 1.29 是 mainline，已被 1.31 取代、不再更新（#85）。
# 寫到小版本而不是 1.30.5：官方會用新的 Alpine 重 build 同一個 tag，重新 build 就拿到作業系統的修補
# -otel 是同一版 nginx 多裝了官方的 OpenTelemetry 模組（ngx_otel_module）：nginx 自己開一個 span、
# 把 traceparent 傳給 app，trace 才從 nginx 開始，而不是從 app 才開始（#31，設定見 nginx.conf 開頭）
FROM nginx:1.30-alpine-otel AS runtime

# 官方映像檔重 build 之前，Alpine 已經發了修補的套件（expat、pcre2、tiff…）先自己升。
# 代價是同一份 Dockerfile 不同天 build 出的套件版本可能不同 —— 換來的是不必等上游
RUN apk upgrade --no-cache

# 模組裝了但沒載入：load_module 只能寫在主設定檔的最上層，conf.d 裡的檔案是被 include 在 http { } 裡面的，寫不進去。
# 所以在官方的 nginx.conf 第一行插一行，其他照用官方的
RUN sed -i '1i load_module modules/ngx_otel_module.so;' /etc/nginx/nginx.conf

# 官方映像檔會把 /etc/nginx/conf.d/*.conf 載入，蓋掉它附的 default.conf 就換成我們的設定
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html

# 不必自己寫 ENTRYPOINT / USER：官方映像檔的 nginx 主程序以 root 起來只為了綁 80 埠，
# 真正處理請求的 worker 是 nginx 這個一般使用者。
# 版本（#25）：跟 app 映像檔同一組，由 compose 的 build.args 傳進來（cd/version.sh 算的）。
# 前端只貼 label、不寫進頁面：版本給維運的人查，不給使用者看（理由見 docs/release.md）
ARG APP_VERSION=local
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="talkcal-web" \
      org.opencontainers.image.source="https://github.com/Anderson-chen/talkcal" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$GIT_COMMIT"

EXPOSE 80
