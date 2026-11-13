# 部署用的 PostgreSQL：官方映像檔再 apk upgrade。
#
# 只用官方的 tag 的話，Alpine 發了修補到官方重新 build 之間，CI 的漏洞掃描每天紅燈，
# 只能一條一條寫例外等上游。代價是同一份 Dockerfile 不同天 build 出的套件版本可能不同。
# 只多升套件，其他照官方的，現有的 postgres-data volume 直接接得上。
# 整合測試（Testcontainers）還是用官方映像檔：測的是 SQL 行為，跟這些套件的版本無關。
FROM postgres:18.6-alpine

RUN apk upgrade --no-cache
