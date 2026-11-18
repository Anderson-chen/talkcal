-- 會員等級：free 是免費，paid 是付費。沒登入的訪客沒有帳號，不在這張表。
--
-- 還沒串接金流，付費會員由維護者直接執行 SQL 指定（見 backend/README.md 的「會員等級」）。
-- 所以值的限制放在資料庫：手動輸入 'Paid'、'gold' 這類寫錯的值，CHECK 會拒絕寫入，資料庫不會存進 `MembershipTier.of` 無法轉換的值。
-- DEFAULT 'free' 讓既有的帳號加上欄位時都是免費會員。

-- 等鎖或執行太久就放棄，不讓 migration 卡住線上的請求（為什麼用 SET LOCAL 見 V12）。
-- 加欄位要鎖整張 account，CHECK 也要掃過每一列確認既有資料都合規；帳號表不大，正常情況下不會超過時限。
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

ALTER TABLE account ADD COLUMN tier TEXT NOT NULL DEFAULT 'free'
    CONSTRAINT account_tier_known CHECK (tier IN ('free', 'paid'));
