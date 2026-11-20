-- 訪客登入正式帳號時把卡片搬過去、清掉太久沒用的訪客時刪掉，都依 owner_id 找 assistant_proposal 的列（JdbcOwnedDataAdapter）。
-- V12 只有 created_at 的索引，沒有這個的話每次都要掃整張表。
--
-- 等鎖或執行太久就放棄，不讓 migration 卡住線上的請求（為什麼用 SET LOCAL 見 V12）
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

-- 不用 CONCURRENTLY：Flyway 執行 migration 時另一條連線開著交易拿鎖，CONCURRENTLY 要等所有交易結束，兩邊互等、卡住不動。
-- 一般的 CREATE INDEX 建立期間會擋這張表的寫入；卡片只留到歸檔（calendar.assistant.retention，30 天）就刪，
-- 表很小，建索引在 statement_timeout 之內就完成，擋寫入的時間很短
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX assistant_proposal_owner_id ON assistant_proposal (owner_id);
