-- 把標題的 CHECK 補到跟 domain（CalendarEvent：String.isBlank）一樣嚴。
--
-- V2 用的是 btrim(title) <> ''，但 btrim 預設只去掉半形空格。實測放得進來的「空白標題」：
--   換行 E'\n'、tab E'\t'、全形空白 U&'\3000'（中文輸入法很容易打出來）
-- 改成「至少有一個非空白字元」：title ~ '\S'。上面三種都擋得住，結果跟 Java 的 isBlank() 一致。
--
-- 為什麼是新的 V4 而不是回頭改 V2：跑過的 migration 不能改。
-- Flyway 會記下每個檔案的 checksum，改了 V2 的話，已經跑過 V2 的資料庫一啟動就驗證失敗；
-- 就算騙過了，已經建好的表也不會因此改變。資料庫的變化只能往前加。
--
-- 舊的 CHECK 是 V2 寫在欄位上、沒取名字的，PostgreSQL 自動命名成 calendar_event_title_check。
-- 這次明確取名，以後要再改就不必去查自動產生的名字。
-- 現有資料要是有違規的列，ADD CONSTRAINT 會失敗、整個 migration rollback（PostgreSQL 的 DDL 有交易），
-- 不會留下「舊的拆了、新的沒裝上」的半套狀態。

ALTER TABLE calendar_event
    DROP CONSTRAINT calendar_event_title_check,
    ADD CONSTRAINT calendar_event_title_not_blank CHECK (title ~ '\S');
