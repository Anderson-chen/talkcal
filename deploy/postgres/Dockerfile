# 部署用的 PostgreSQL：官方映像檔，再補上 Alpine 已經發、官方還沒重新 build 進來的修補（#289）。
#
# 只用官方的 tag 時，Alpine 發了修補到官方重新 build 之間，CI 的漏洞掃描每天紅燈，
# 只能一條一條寫例外等上游（#264 寫了 18 條，隔天又多一個）。自己升的做法和代價跟 web/Dockerfile 一樣。
# 其他都照官方的：entrypoint、使用者、PGDATA、healthcheck 一樣，現有的 postgres-data volume 直接接得上。
# 整合測試（Testcontainers）還是用官方映像檔：測的是 SQL 行為，跟這些套件的版本無關。
#
# 版本跟 deploy/compose.yaml 的 image tag 是同一組，Renovate 兩處一起改（.github/renovate.json5）
FROM postgres:18.6-alpine

RUN apk upgrade --no-cache
