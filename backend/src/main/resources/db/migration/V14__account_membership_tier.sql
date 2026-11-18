-- 會員等級：free（免費）、paid（付費）。沒登入的訪客沒有帳號，不在這張表。
--
-- 還沒接金流，付費會員由維護者直接下 SQL 指定（見 backend/README.md 的「會員等級」）。
-- 所以值的限制放在資料庫：手打 'Paid'、'gold' 這類寫錯的值，CHECK 會擋下來，不會變成程式讀不懂的帳號。
-- DEFAULT 'free' 讓既有的帳號加上欄位時都是免費會員。
ALTER TABLE account ADD COLUMN tier TEXT NOT NULL DEFAULT 'free'
    CONSTRAINT account_tier_known CHECK (tier IN ('free', 'paid'));
