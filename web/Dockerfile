# build 階段編出 dist/，runtime 階段只有 nginx：Node、node_modules、原始碼都不進上線的映像檔。

# ════════════════════════════════════════════════════════════════════
# 跟本機開發同一個大版本
FROM node:22-alpine AS build
WORKDIR /src

# pnpm 由 corepack 照 package.json 的 packageManager 下載（理由見 README.md）。不關掉提示的話，下載前會停下來問 Y/n。
# pnpm 本體下載在獨立的映像檔層，只有 package.json 改了才重新下載。放 BuildKit 的快取不行：
# 快取只掛在那一個 RUN 上，後面的 pnpm run build 讀不到，又會再下載一次
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
COPY package.json ./
RUN corepack enable && corepack install

# 先只複製決定依賴的檔案：只改 src/ 時這層快取不失效，不必重裝依賴。
# --frozen-lockfile：lock 跟 package.json 對不上就失敗，不會自行升級（TypeScript 釘在 5.9 的理由見 README.md）。
# store 放 BuildKit 的快取，依賴改了重裝時，沒變的套件不必重新下載
COPY pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm-store pnpm_config_store_dir=/pnpm-store pnpm install --frozen-lockfile

COPY . .
# build 會先執行 vue-tsc，型別錯誤的前端進不了映像檔
RUN pnpm run build

# ════════════════════════════════════════════════════════════════════
# 1.30 是 stable 線，只收安全和錯誤修補；mainline（奇數小版本）出了下一版，上一版就不再更新。
# 只寫到小版本：官方會用新的 Alpine 重新 build 同一個 tag，重新 build 就拿到作業系統的修補。
# -otel 多裝了官方的 OpenTelemetry 模組（ngx_otel_module），設定見 nginx.conf 的 trace 那段
FROM nginx:1.30-alpine-otel AS runtime

# 官方映像檔重新 build 之前，Alpine 可能已經發了修補的套件（expat、pcre2、tiff…）。
# 代價是同一份 Dockerfile 不同天 build 出的套件版本可能不同
RUN apk upgrade --no-cache

# load_module 只能寫在主設定檔的最上層，conf.d 的檔案被 include 在 http { } 裡，寫不進去
RUN sed -i '1i load_module modules/ngx_otel_module.so;' /etc/nginx/nginx.conf

# 官方映像檔載入 /etc/nginx/conf.d/*.conf，蓋掉附的 default.conf 就換成這份設定
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html

# 不必另外設 USER：官方映像檔的 master process 以 root 啟動只為了綁 80 埠，處理請求的 worker 是一般使用者 nginx。
# 版本跟 app 映像檔同一組，由 compose 的 build.args 傳入（cd/version.sh 算出）。
# 只貼 label、不寫進頁面：版本給維運查，不給使用者看（理由見 cd/release.md）
ARG APP_VERSION=local
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="talkcal-web" \
      org.opencontainers.image.source="https://github.com/Anderson-chen/talkcal" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$GIT_COMMIT"

EXPOSE 80
