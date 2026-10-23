# talkcal 前端的映像檔。跟 app 的 Dockerfile（backend/Dockerfile）一樣是兩段式——
#   build   —— Node + npm，把 .vue / .ts 編成靜態檔（dist/）
#   runtime —— 只有 nginx，送靜態檔、把 /api 轉給 app。Node、node_modules、原始碼都不會跟著上雲端。

# ════════════════════════════════════════════════════════════════════
# 跟本機開發同一個大版本（node -v 是 22）
FROM node:22-alpine AS build
WORKDIR /src

# 先只放「決定依賴是什麼」的兩個檔案——只改 src/ 時這層快取不失效，不必每次重裝依賴。
# npm ci 而不是 npm install：嚴格照 package-lock.json 裝，lock 跟 package.json 對不上就直接失敗，
# 不會偷偷升級（TypeScript 釘在 5.9 的理由見 web/README.md）。
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
# build 會先跑 vue-tsc 型別檢查：型別錯就 build 失敗，壞的前端進不了映像檔
RUN npm run build

# ════════════════════════════════════════════════════════════════════
# 1.30 是 nginx 的 stable 線，只收安全和錯誤修補；原本的 1.29 是 mainline，已被 1.31 取代、不再更新（#85）。
# 寫到小版本而不是 1.30.5：官方會用新的 Alpine 重 build 同一個 tag，重新 build 就拿到作業系統的修補
FROM nginx:1.30-alpine AS runtime

# 官方映像檔重 build 之前，Alpine 已經發了修補的套件（expat、pcre2、tiff…）先自己升。
# 代價是同一份 Dockerfile 不同天 build 出的套件版本可能不同 —— 換來的是不必等上游
RUN apk upgrade --no-cache

# 放成 template：官方映像檔啟動時把 /etc/nginx/templates/*.template 的 ${環境變數} 換掉，
# 寫成 /etc/nginx/conf.d/default.conf（蓋掉它附的那份）。同一個映像檔在 compose 和 k8s 上都能跑（#56），差別只在環境變數。
COPY nginx.conf /etc/nginx/templates/default.conf.template
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS：讓官方的啟動腳本從 /etc/resolv.conf 讀出 DNS，填進 NGINX_LOCAL_RESOLVERS
# NGINX_ENVSUBST_FILTER：只換這兩個名字。不限的話，任何剛好跟 nginx 變數同名的環境變數都會被換掉
# TALKCAL_APP_UPSTREAM：compose 的預設值；k8s 由 Deployment 的 env 蓋掉
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1     NGINX_ENVSUBST_FILTER="^(TALKCAL_APP_UPSTREAM|NGINX_LOCAL_RESOLVERS)$"     TALKCAL_APP_UPSTREAM=app:8090
COPY --from=build /src/dist /usr/share/nginx/html

# 不必自己寫 ENTRYPOINT / USER：官方映像檔的 nginx 主程序以 root 起來只為了綁 80 埠，
# 真正處理請求的 worker 是 nginx 這個一般使用者。
EXPOSE 80
