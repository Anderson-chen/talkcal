-- R3 第 3 支：phone_number 收緊成 NOT NULL，不在拿著 ACCESS EXCLUSIVE 鎖的時候掃表。
--
-- 直接 SET NOT NULL 會拿著 ACCESS EXCLUSIVE 掃整張表確認沒有 NULL。先 VALIDATE V3 的 CHECK
-- （只要 SHARE UPDATE EXCLUSIVE 鎖，掃表時讀寫照常），PostgreSQL 12 起 SET NOT NULL 看到驗證過的 IS NOT NULL CHECK 就不再掃表。
-- 之後那個 CHECK 就多餘了，拿掉。

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE drill_contact
    VALIDATE CONSTRAINT drill_contact_phone_number_present;
ALTER TABLE drill_contact
    ALTER COLUMN phone_number SET NOT NULL;
ALTER TABLE drill_contact
    DROP CONSTRAINT drill_contact_phone_number_present;
