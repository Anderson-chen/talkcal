-- 月曆查的是「跟這段期間有重疊的行程」：start_at < 期間結束 AND end_at > 期間開始。
--
-- 一般的 btree 索引建在 start_at 上，只幫得了前半句：
-- 「在期間結束之前開始的行程」會隨著時間越來越多（過去所有行程都符合），等於越用越接近全表掃描。
-- 兩個欄位各建一個 btree 也一樣，兩個條件都是開放的一側，各自都會挑出一大堆。
--
-- PostgreSQL 處理這種問題的工具是「範圍型別」：把起訖包成一個 tsrange，用 && （重疊）查，
-- 再在那個運算式上建 GiST 索引 —— GiST 能把「範圍」組織成一棵樹，重疊查詢只走相關的枝。
-- tsrange(a, b) 預設的邊界是 [a, b)（含開始、不含結束），跟 domain 的 DateRange、CalendarEvent 的語意一致：
-- 剛好在期間開始那一刻結束的行程不算重疊。
--
-- 索引建在運算式 tsrange(start_at, end_at) 上，而不是另外多存一個 range 欄位：
-- 欄位維持兩個簡單的 TIMESTAMP（V2 的 CHECK、adapter 的 INSERT 都不必改），
-- 只要查詢時寫出一模一樣的運算式，規劃器就認得出能用這個索引。
-- 這也是為什麼這件事是一個新的 V3，而不是回頭改 V2：跑過的 migration 不能改，資料庫的變化只能往前加。

CREATE INDEX calendar_event_period ON calendar_event USING gist (tsrange(start_at, end_at));
