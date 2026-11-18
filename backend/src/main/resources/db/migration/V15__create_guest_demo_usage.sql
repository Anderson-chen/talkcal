-- 沒登入的訪客每個 IP 每天能看幾次助理的示範回答。
--
-- 計數放資料庫，不放 app 的記憶體：app 有好幾個副本時，記憶體各算各的，同一個 IP 換一台就又有 3 次；app 重啟也會歸零。
-- nginx 的 limit_req 最慢只能設每分鐘，算不了「每天」。
--
-- 主鍵 (client, day) 讓加一和檢查上限在同一句 SQL 完成（INSERT ... ON CONFLICT DO UPDATE ... WHERE used < 上限），
-- 同一個 IP 同時送好幾個請求，資料庫也只會讓前幾個成功，不會先查到「還有」再一起加過頭。
--
-- client 是 IP 字串，day 是 calendar.zone 的日期（台北的午夜換日，不是 UTC 的）。
-- 過了當天就沒有用處，由 GuestDemoUsageCleanup 每天刪掉前一天以前的，IP 不留超過兩天。

-- 等鎖或執行太久就放棄，不讓 migration 卡住線上的請求（為什麼用 SET LOCAL 見 V12）。
-- 這張表是這支 migration 才建的、裡面沒有資料，實際上不會等
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

-- 長度用 TEXT + CHECK，理由見 V13。IPv6 最長 45 個字，帶著 zone id（%eth0）會再長一點
CREATE TABLE guest_demo_usage (
    client TEXT    NOT NULL CHECK (char_length(client) BETWEEN 1 AND 100),
    day    DATE    NOT NULL,
    used   INTEGER NOT NULL CHECK (used > 0),
    PRIMARY KEY (client, day)
);

-- 每天的清理用 day 找舊的列；主鍵以 client 開頭，用不到
CREATE INDEX guest_demo_usage_day ON guest_demo_usage (day);
