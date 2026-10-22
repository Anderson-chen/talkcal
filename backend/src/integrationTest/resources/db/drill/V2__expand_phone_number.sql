-- R2（expand）：程式 v2 上線，雙寫 phone、phone_number，還讀 phone。部署途中、退回時 v1 還在線上。
--
-- 這支只「加東西」，所以 v1 碰到它也照常：v1 的 INSERT 不給 phone_number，就是 NULL。
-- 不在這裡補舊資料、不加 NOT NULL：v1 還在寫 NULL 進來，補了也會再出現（見 R3 的前提）。

-- 拿不到鎖就放棄、整支 rollback，不排隊：ALTER TABLE 排 ACCESS EXCLUSIVE 鎖時，後面所有讀寫這張表的查詢也會排在它後面
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- 可為 NULL、沒有預設值：只改目錄（catalog），不掃表、不重寫
ALTER TABLE drill_contact
    ADD COLUMN phone_number TEXT;

-- 舊欄位放寬成可為 NULL：R4 的 v4 只寫新欄位，那時 phone 必須接受 NULL。DROP NOT NULL 只改目錄。
-- squawk 擋它的理由是「讀的人可能沒料到 NULL」：這裡第一筆 NULL 要到 R4 才會出現，
-- 那時還讀 phone 的 v1、v2 都已經不在線上（R4 的前提），所以趁 expand 先放寬，v1 照樣給值不受影響
-- squawk-ignore ban-drop-not-null
ALTER TABLE drill_contact ALTER COLUMN phone DROP NOT NULL;
