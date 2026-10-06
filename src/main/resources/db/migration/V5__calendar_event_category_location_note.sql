-- 行程加上分類、地點、備註（照設計稿）。
--
-- category：NOT NULL，舊資料用 DEFAULT 補成 PERSONAL —— 跟 domain 的 Category.DEFAULT 一致。
--   CHECK 的值跟 Category enum 的名稱一致；enum 加新值時，這裡要跟著一個新的 migration。
-- location、note：選填，NULL 就是沒有。domain 把空白一律當成沒有，所以 CHECK 擋掉「存了空白字串」，
--   讓資料庫裡「沒有」只有一種寫法（NULL），查詢時不必 WHERE location IS NULL OR location = ''。
--   ~ '\S' 跟 V4 標題的寫法一樣，空格、換行、全形空白都算空白。

ALTER TABLE calendar_event
    ADD COLUMN category TEXT NOT NULL DEFAULT 'PERSONAL'
        CONSTRAINT calendar_event_category_known CHECK (category IN ('WORK', 'PERSONAL', 'HEALTH', 'SOCIAL')),
    ADD COLUMN location TEXT
        CONSTRAINT calendar_event_location_not_blank CHECK (location ~ '\S'),
    ADD COLUMN note     TEXT
        CONSTRAINT calendar_event_note_not_blank CHECK (note ~ '\S');
