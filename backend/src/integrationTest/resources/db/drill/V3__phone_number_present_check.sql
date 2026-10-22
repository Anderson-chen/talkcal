-- R3（backfill、切讀）第 1 支：程式 v3 上線，雙寫、改讀 phone_number。部署途中、退回時 v2 還在線上。
--
-- R3 的前提：v1 已經不在線上、也不會被退回去。v2、v3 都雙寫，從這一刻起不會再有人寫進 NULL 的 phone_number，
-- 所以這次發布才能補舊資料、收緊成 NOT NULL。前提不成立時（v1 還在），v1 的 INSERT 會被這支的 CHECK 擋下。
--
-- 收緊 NOT NULL 拆成三支（V3 加 CHECK、V4 補資料、V5 驗證後 SET NOT NULL），因為 Flyway 一支一個交易：
-- ADD CONSTRAINT 拿到的 ACCESS EXCLUSIVE 會拿到交易結束，跟掃表的 UPDATE、VALIDATE 放同一支，整段讀寫都卡住。

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- NOT VALID：只檢查之後寫進來的列，不在拿著鎖的時候掃既有資料（既有的在 V5 驗）
ALTER TABLE drill_contact
    ADD CONSTRAINT drill_contact_phone_number_present CHECK (phone_number IS NOT NULL) NOT VALID;
