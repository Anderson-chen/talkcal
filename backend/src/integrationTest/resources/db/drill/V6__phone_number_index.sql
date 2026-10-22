-- R3 第 4 支：v3 改用 phone_number 查，給它索引。
--
-- CONCURRENTLY：一般的 CREATE INDEX 整段拿著 SHARE 鎖擋寫入，大表就是好幾分鐘不能寫。CONCURRENTLY 不擋讀寫，代價是較慢、不能在交易裡跑。
-- 不能在交易裡跑，所以這支只能有這一類句子：Flyway 偵測到 CONCURRENTLY 會把整支放到交易外面，混了 SET 也會被拒絕
-- （Detected both transactional and non-transactional statements），所以沒有 lock_timeout。
-- CONCURRENTLY 要的 SHARE UPDATE EXCLUSIVE 鎖排隊時不擋一般讀寫，沒有 timeout 的代價小得多；
-- statement_timeout 本來就不該給：大表建索引就是要跑很久，半路砍掉只會留下 INVALID 的半成品。
--
-- 沒有交易就不會 rollback：失敗可能留下 INVALID 的半成品索引，所以先 DROP ... IF EXISTS，修好原因、flyway repair 之後重跑會先清掉。
-- 另外 Flyway 要用連線層級的鎖（spring.flyway.postgresql.transactional-lock=false），不然 CONCURRENTLY 會等 Flyway 自己的交易，永遠跑不完。

-- squawk-disable-assume-in-transaction
-- squawk-ignore require-lock-timeout, require-statement-timeout
DROP INDEX CONCURRENTLY IF EXISTS drill_contact_phone_number;
-- squawk-ignore require-lock-timeout, require-statement-timeout
CREATE INDEX CONCURRENTLY IF NOT EXISTS drill_contact_phone_number
    ON drill_contact (phone_number);
