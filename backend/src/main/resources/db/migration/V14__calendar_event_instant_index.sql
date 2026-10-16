-- 月曆查詢改走新欄位之後要用的索引（#18）。跟 V11 的 calendar_event_owner_period 一樣，只是換成時間點的 tstzrange。
--
-- CONCURRENTLY：一般的 CREATE INDEX 建索引的整段時間都擋住寫入（SHARE 鎖），表大的時候就是好幾分鐘不能新增行程。
-- CONCURRENTLY 不擋讀寫，代價是要掃兩次表、比較慢，而且不能在交易裡跑。
--
-- 不能在交易裡跑，所以這支 migration 只能有這一類的句子：Flyway 偵測到 CONCURRENTLY 會把整支放到交易外面執行，
-- 跟一般的 DDL 混在同一支會直接報錯。也因為沒有交易，失敗時不會 rollback：
-- 可能留下一個 INVALID 的半成品索引，Flyway 的紀錄也會停在「失敗」—— 所以先 DROP ... IF EXISTS，
-- 修好原因、flyway repair 之後重跑，這支會先把半成品清掉再重建。
--
-- 另外要 spring.flyway.postgresql.transactional-lock=false（見 application.properties）：
-- Flyway 預設用「交易層級」的 advisory lock 防止兩個 app 同時跑 migration，
-- 而 CONCURRENTLY 要等所有進行中的交易結束，包括 Flyway 自己拿著鎖的那個 —— 互等，部署就卡住。
--
-- V11 那個舊索引先留著：舊版程式（部署途中、退回上一版時）查的還是 start_at、end_at，要靠它。contract 那一步才拿掉。

-- 這支沒有 SET lock_timeout、statement_timeout（其他支都有，squawk 也要求）：Flyway 把 SET 當成要在交易裡跑的句子，
-- 放進來就變成「混在一起」被拒絕（實測：Detected both transactional and non-transactional statements）。
-- CONCURRENTLY 要的是 SHARE UPDATE EXCLUSIVE 鎖，排隊時不擋一般的讀寫，沒有 timeout 的代價比 ALTER TABLE 小得多；
-- statement_timeout 本來就不該給：大表建索引就是要跑很久，半路砍掉只會留下 INVALID 的半成品。

-- squawk-disable-assume-in-transaction
-- squawk-ignore require-lock-timeout, require-statement-timeout
DROP INDEX CONCURRENTLY IF EXISTS calendar_event_owner_instant;
-- squawk-ignore require-lock-timeout, require-statement-timeout
CREATE INDEX CONCURRENTLY IF NOT EXISTS calendar_event_owner_instant
    ON calendar_event USING gist (owner_id, tstzrange(starts_at, ends_at));
