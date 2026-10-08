-- 行事曆的行程：一筆 ScheduledEvent 一列。
-- 跟 conversation 的兩張表沒有任何外鍵 —— 兩個模組在資料庫裡也互不認識，跟 ArchitectureTest 的模組規則一致。

CREATE TABLE calendar_event (
    id         UUID        PRIMARY KEY,             -- EventId：domain 自己發的 UUID，不靠資料庫流水號
    -- 跟 CalendarEvent 的規則一致。CHECK 擋的是手動塞進來的怪值，真正的規則在 domain
    title      TEXT        NOT NULL CHECK (btrim(title) <> ''),
    -- 不叫 start / end：END 是 SQL 的保留字，當欄位名每次都得加引號。
    -- TIMESTAMP 不帶時區，對應 LocalDateTime（台北的牆上時間）。用 TIMESTAMPTZ 的話，
    -- 資料庫會依連線的時區設定換算，等於偷偷加回「不處理時區」這個決定排除掉的東西
    start_at   TIMESTAMP   NOT NULL,
    end_at     TIMESTAMP   NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 這個是「什麼時候寫進來的」，是真實時間點，所以帶時區
    CHECK (end_at > start_at)
);
