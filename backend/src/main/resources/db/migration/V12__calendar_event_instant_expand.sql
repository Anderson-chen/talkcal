-- 行程的起訖時間改存「絕對時間點」（TIMESTAMPTZ）—— expand/contract 的 expand（#18）。
--
-- 為什麼要改：V2 的 start_at、end_at 是不帶時區的 TIMESTAMP，存的是「台北的牆上時間」，
-- 但資料庫自己不知道它是台北：要寄提醒（「15:00 開會」該在哪個瞬間通知）、使用者不在台北、
-- 或是跟別的系統交換時間，每個讀的人都得自己記得「這是 Asia/Taipei」。改存時間點，值本身就說得清楚。
-- domain 還是 LocalDateTime（單一時區的決定不變，見 CalendarEvent），換算只在 adapter 做一次。
--
-- 為什麼不直接 ALTER COLUMN start_at TYPE TIMESTAMPTZ：
--   1. 會重寫整張表，期間拿著 ACCESS EXCLUSIVE 鎖，讀寫全部卡住。
--   2. 部署時舊版還在線上（滾動更新、或退回上一版），它照樣送不帶時區的值進來，
--      PostgreSQL 會用「連線的時區」把它轉成時間點 —— app 跑在 UTC 的機器上，15:00 就被存成台北 23:00，不報錯、默默錯 8 小時。
-- 所以照 expand/contract 走：舊欄位不動，旁邊加新欄位，新舊版本都能用；確定沒有人讀舊欄位了才拿掉
-- （完整的步驟和還沒做的 contract 見 docs/database/zero-downtime-schema.md）。
--
-- 這支只做「加東西」，不碰既有資料：補舊資料是 V13，建索引是 V14，各自一支，鎖不會疊在一起。

-- 拿不到鎖就 5 秒後放棄、整支 rollback，而不是排隊等：
-- ALTER TABLE 要的 ACCESS EXCLUSIVE 鎖在排隊時，後面所有讀寫這張表的查詢也會排在它後面，等於停機。
-- 用 SET LOCAL：只在這支 migration 的交易裡有效，不會留在連線上影響之後的查詢
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- 新欄位先允許 NULL、不給預設值：只改目錄（catalog），不必掃表、不重寫，拿到鎖就幾乎馬上結束。
-- 名字跟舊的差一個字母（start_at → starts_at）：舊欄位之後會被拿掉，新名字是要長久留下來的那個，不加 _tz 之類的過渡字尾
ALTER TABLE calendar_event
    ADD COLUMN starts_at TIMESTAMPTZ,
    ADD COLUMN ends_at   TIMESTAMPTZ;

-- 兩個 CHECK 都加成 NOT VALID：只檢查之後寫進來的列，不在拿著鎖的時候掃既有資料。
-- 既有的列在 V13 補完值之後再 VALIDATE（那一步只要 SHARE UPDATE EXCLUSIVE 鎖，不擋讀寫）。
-- 一定要放在這裡、不能在 V13 才加：同一個交易裡 ADD CONSTRAINT 拿到的 ACCESS EXCLUSIVE 鎖會拿到交易結束，
-- 緊接著 VALIDATE 掃表的時候讀寫就全卡住，NOT VALID 白做（squawk 的 constraint-missing-not-valid 抓到的）
--
-- 跟 V2 的 CHECK (end_at > start_at) 同一條規則
ALTER TABLE calendar_event
    ADD CONSTRAINT calendar_event_instant_order CHECK (ends_at > starts_at) NOT VALID;
-- 讓 V13 能不掃表就把新欄位改成 NOT NULL 的墊腳石（理由在 V13）。之後寫進來的列 trigger 都會補上值，所以現在就擋得住
ALTER TABLE calendar_event
    ADD CONSTRAINT calendar_event_instants_present CHECK (starts_at IS NOT NULL AND ends_at IS NOT NULL) NOT VALID;

-- 舊版程式只認得 start_at、end_at，寫進來的列新欄位會是 NULL。
-- 這個 trigger 讓「只寫舊欄位的人」也自動寫到新欄位 —— 部署途中、或退回上一版時，舊版寫的資料新版照樣讀得到。
-- 新版自己兩邊都寫（雙寫），trigger 看到新欄位已經有值就不動。
-- 時區寫死 Asia/Taipei：跟 domain「台北的牆上時間」是同一個決定（JdbcCalendarEventAdapter.ZONE），兩邊要一起改
CREATE FUNCTION calendar_event_sync_instants() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.starts_at := COALESCE(NEW.starts_at, NEW.start_at AT TIME ZONE 'Asia/Taipei');
        NEW.ends_at := COALESCE(NEW.ends_at, NEW.end_at AT TIME ZONE 'Asia/Taipei');
    ELSE
        -- UPDATE：只改了舊欄位（舊版程式）就跟著換算；新欄位也一起改了（新版程式）就照新版的
        IF NEW.start_at IS DISTINCT FROM OLD.start_at AND NEW.starts_at IS NOT DISTINCT FROM OLD.starts_at THEN
            NEW.starts_at := NEW.start_at AT TIME ZONE 'Asia/Taipei';
        END IF;
        IF NEW.end_at IS DISTINCT FROM OLD.end_at AND NEW.ends_at IS NOT DISTINCT FROM OLD.ends_at THEN
            NEW.ends_at := NEW.end_at AT TIME ZONE 'Asia/Taipei';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER calendar_event_sync_instants
    BEFORE INSERT OR UPDATE ON calendar_event
    FOR EACH ROW
EXECUTE FUNCTION calendar_event_sync_instants();
