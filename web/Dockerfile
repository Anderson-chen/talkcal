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

# 官方映像檔會把 /etc/nginx/conf.d/*.conf 載入，蓋掉它附的 default.conf 就換成我們的設定
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html

# 不必自己寫 ENTRYPOINT / USER：官方映像檔的 nginx 主程序以 root 起來只為了綁 80 埠，
# 真正處理請求的 worker 是 nginx 這個一般使用者。
EXPOSE 80
