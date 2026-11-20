# 0012. 每日次數：規則放 PostgreSQL，計數放 Redis

- 狀態：已採用
- 日期：2026-11-19
- 相關：issue [#331](https://github.com/Anderson-chen/talkcal/issues/331)（訪客模式）、[#332](https://github.com/Anderson-chen/talkcal/issues/332)（免費、付費的次數）、PR [#339](https://github.com/Anderson-chen/talkcal/pull/339)（先前照 IP 計數的 `guest_demo_usage` 表）、[0010](0010-aws-stage-one-ec2-compose-bedrock.md)

## 背景

限流原本只有 nginx 的 `limit_req`（[web/nginx.conf](../../web/nginx.conf)），擋的是每個 IP 每分鐘問幾次助理。
#331 開放訪客自由問 AI 助理、增刪改行程，要的是另一種限制：

- 每天幾次，換日歸零，日期照 `calendar.zone`。
- 照會員等級分開：訪客、免費、付費的上限不同，#332 要照帳號計數。
- 規則會一直加：新增一個要限制的 API，最好不用改程式。
- 階段一只有一台 EC2（0010），之後可能多個 app 副本，副本之間要看到同一份計數。

## 選項

2026-11-20 查證：

| | nginx `limit_req` | 計數放 PostgreSQL | 計數放 app 的記憶體 | 計數放 Redis |
|---|---|---|---|---|
| 每天幾次 | 做不到：`rate` 只能寫每秒（r/s）、每分鐘（r/m） | 做得到 | 做得到 | `INCR` 加一，key 帶日期、到期設在午夜之後 |
| 照等級、照帳號 | 做不到：nginx 看不到登入的是誰 | 做得到 | 做得到 | 做得到（等級在 app 比對規則時決定） |
| 每個受限的請求多做什麼 | 沒有 | 一次資料庫寫入（PR #339 的 `guest_demo_usage` 就是這樣） | 沒有 | 一次 Redis 往返，通常 1 ms 內 |
| 多個 app 副本 | 不受影響 | 共用 | 各數各的，副本越多額度越多 | 共用同一個 Redis |
| 重啟之後 | 不受影響 | 都在 | 歸零 | 開 AOF、每秒寫一次磁碟，最多少掉約 1 秒 |
| 多一個要維運的服務 | 不用 | 不用 | 不用 | 要 |

來源：[nginx：ngx_http_limit_req_module](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)（`rate` 的單位）、
[Redis：INCR](https://redis.io/docs/latest/commands/incr/)（rate limiter 的寫法）、
[Redis：persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)（`appendfsync everysec` 最多少 1 秒）。

## 決定

- 規則放 PostgreSQL 的 `quota_rule`、`quota_bucket`（[V15](../../backend/src/main/resources/db/migration/V15__guest_mode_and_daily_quota.sql)）：
  哪個方法和路徑、哪個等級、照 IP 或帳號或全站、每天幾次。新增一個要限制的 API 只要加一列，app 定時重讀。
- 計數放 Redis（[`RedisQuotaCounterAdapter`](../../backend/src/main/java/io/github/andersonchen/talkcal/quota/adapter/out/counter/redis/RedisQuotaCounterAdapter.java)）：
  加一和設到期在同一段 script。compose 多一個 `redis` 服務，開 AOF（`appendfsync everysec`）並掛 volume。
- 比對規則、扣次數、退回在 Spring MVC 的 interceptor（[`QuotaInterceptor`](../../backend/src/main/java/io/github/andersonchen/talkcal/quota/adapter/in/web/QuotaInterceptor.java)），
  這時已經知道登入的是誰、選好了 controller 的方法。
- nginx 的 `limit_req` 留著，擋每分鐘的連發。

不放 PostgreSQL：每個受限的請求都要加一，等於每個請求多一次寫入，PR #339 的計數表就是這樣。
不放 app 的記憶體：重啟歸零，多個副本各數各的。

## 代價

- 多一個服務要升級、監看；映像檔版本跟整合測試的 `TestRedis.IMAGE` 一起改（Renovate 綁在一起）。
- Redis 無法連線時放行（`ConsumeQuotaUseCase`）：那段時間不限制次數，換來 Redis 停止回應時整站照常可用。
- 計數的 volume 不備份：整個不見的話，當天的次數重新算。
- 規則比對的是 controller 上的路徑樣板，`quota_rule.path_pattern` 要跟 `@RequestMapping` 一字不差（見 `QuotaRule`）。

## 什麼時候重想

- app 跨機器、跨 AZ，或不想自己管 Redis：換成 ElastiCache、MemoryDB。
- Redis 停機時不限制次數變得不能接受（例如付費方案按次數收錢）：改成無法連線時擋下，或計數改進資料庫。
- 受限的請求多到一台 Redis 撐不住：改用 Redis Cluster。

## 證據

- [V15](../../backend/src/main/resources/db/migration/V15__guest_mode_and_daily_quota.sql)：規則表的定義和為什麼計數不在資料庫
- [`deploy/compose.yaml`](../../deploy/compose.yaml) 的 `redis` 服務：AOF、volume、app 不 `depends_on` Redis 的理由
- [`RedisQuotaCounterAdapterTest`](../../backend/src/integrationTest/java/io/github/andersonchen/talkcal/quota/adapter/out/counter/redis/RedisQuotaCounterAdapterTest.java)：兩個 app 執行個體共用計數、Redis 重啟之後次數還在
- [`GuestModeSystemTest`](../../backend/src/integrationTest/java/io/github/andersonchen/talkcal/GuestModeSystemTest.java)：整個 app 接起來，第 4 次問助理、第 11 次增刪改被擋
