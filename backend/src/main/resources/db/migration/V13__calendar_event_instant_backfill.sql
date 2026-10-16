-- 補上 V12 之前就存在的行程的 starts_at、ends_at，補完再把新欄位收緊成 NOT NULL（#18 的 backfill）。
--
-- 為什麼可以跟 V12 在同一次部署：V12 的 trigger 已經裝上了，從那一刻起不管新版舊版寫進來的列，新欄位都有值；
-- 這支只要處理「trigger 裝上之前」就在的那些。沒有 trigger 的話，backfill 得等到舊版全部下線才能跑，
-- 不然補完之後舊版又寫進一筆 NULL（見 docs/database/zero-downtime-schema.md 的「為什麼用 trigger」）。
--
-- 為什麼跟 V12 分開成兩支：Flyway 一支 migration 一個交易。放在同一支的話，V12 ALTER TABLE 拿到的
-- ACCESS EXCLUSIVE 鎖會一直拿到 UPDATE 整張表做完才放，這段時間所有讀寫都卡住。分開之後，鎖在 V12 結束就放了，
-- 這裡的 UPDATE 只鎖到它改的那幾列。
--
-- 現在的資料量（本機、每個人幾十筆行程）一句 UPDATE 就好。表大到一句會跑很久的時候要改成分批
-- （每批幾千列、各自 commit，做法見文件），一句大 UPDATE 會長時間佔著列鎖、產生大量 WAL、拖慢複寫。

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';

-- WHERE ... IS NULL：重跑也只補還沒補的；trigger 看到新欄位被明確給了值，不會再蓋掉
UPDATE calendar_event
SET starts_at = start_at AT TIME ZONE 'Asia/Taipei',
    ends_at   = end_at AT TIME ZONE 'Asia/Taipei'
WHERE starts_at IS NULL
   OR ends_at IS NULL;

-- V12 加的順序檢查，現在既有的列也有值了，補驗一次。VALIDATE 只拿 SHARE UPDATE EXCLUSIVE 鎖：掃表的時候讀寫照常
ALTER TABLE calendar_event VALIDATE CONSTRAINT calendar_event_instant_order;

-- 收緊成 NOT NULL，但不直接 SET NOT NULL：那會在拿著 ACCESS EXCLUSIVE 鎖的時候掃整張表確認沒有 NULL。
-- V12 先加了 NOT VALID 的 IS NOT NULL CHECK，這裡 VALIDATE（不擋讀寫的那種鎖），PostgreSQL 12 起 SET NOT NULL 看到
-- 「已經驗證過的 IS NOT NULL CHECK」就不再掃表，ACCESS EXCLUSIVE 只拿一瞬間。之後這個 CHECK 就多餘了，拿掉
ALTER TABLE calendar_event VALIDATE CONSTRAINT calendar_event_instants_present;
ALTER TABLE calendar_event
    ALTER COLUMN starts_at SET NOT NULL,
    ALTER COLUMN ends_at SET NOT NULL;
ALTER TABLE calendar_event DROP CONSTRAINT calendar_event_instants_present;
