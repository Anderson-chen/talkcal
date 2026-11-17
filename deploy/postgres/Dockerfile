# 以官方映像檔為基底，再執行 apk upgrade。只用官方的 tag 的話，Alpine 發了修補、官方還沒重新 build 的那段時間，
# CI 的漏洞掃描每天紅燈，只能一條一條寫例外等上游。代價是不同天 build 出來的套件版本可能不同。
# 只升套件，現有的 postgres-data volume 照樣接得上。
# 整合測試（Testcontainers）用官方映像檔就好：測的是 SQL 行為，跟這些套件的版本無關。
FROM postgres:18.6-alpine

RUN apk upgrade --no-cache
