-- expand/contract 演練的起點（#18）。只在整合測試的資料庫跑，不是產品的 schema（產品的在 src/main/resources/db/migration）。
--
-- 演練的題目是「欄位改名」：phone → phone_number。選它是因為它是最常見、也最直接的不相容變更 ——
-- 直接 RENAME COLUMN 的那一刻，還在線上的舊版程式所有讀寫都壞掉。
-- 每支 migration 開頭寫它屬於哪一次發布（R1～R5），發布順序和每一次的前提見 docs/database/zero-downtime-schema.md。
--
-- R1：程式 v1 只認得 phone
CREATE TABLE drill_contact
(
    id    UUID PRIMARY KEY,
    phone TEXT NOT NULL
);
