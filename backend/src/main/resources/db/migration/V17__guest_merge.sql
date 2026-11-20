-- 訪客併進了哪個正式帳號、什麼時候，給合併之後再搬一次用（理由見 FinishGuestMergesUseCase）。再搬一次之後就刪掉這一列。
--
-- 等鎖或執行太久就放棄，不讓 migration 卡住線上的請求（為什麼用 SET LOCAL 見 V12）
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

-- guest_id 不加外鍵：訪客帳號在記下這一列的同一句 SQL 裡就刪掉了（JdbcAccountAdapter.deleteMerged）。
-- target_id 的帳號刪掉時這一列跟著刪：已經沒有帳號可以搬過去
CREATE TABLE guest_merge (
    guest_id  UUID        PRIMARY KEY,
    target_id UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    merged_at TIMESTAMPTZ NOT NULL
);
