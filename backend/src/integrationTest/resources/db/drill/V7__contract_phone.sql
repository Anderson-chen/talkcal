-- R5（contract）：刪掉 phone。程式還是 v4，這次發布只有這支 migration。
--
-- 中間隔了一次 R4：v4 上線、停寫 phone。R4 的前提是 v2（還讀 phone）已經不在線上也不會被退回；
-- 這次的前提是 v3（還寫 phone）也不在了 —— 線上和能退回的都只剩 v4，沒有人碰 phone。
-- 前提不成立時 v3 的 INSERT、UPDATE 會因為欄位不存在而失敗。這一步之後就退不回 v3 了，這是 expand/contract 本來的代價。

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- v4（R4）起沒有任何版本讀寫 phone；會碰它的 v1～v3 都已經不在線上、也不在退回的範圍內。
-- DROP COLUMN 只改目錄、不重寫表（空間等之後重寫表時才回收）
-- squawk-ignore ban-drop-column
ALTER TABLE drill_contact DROP COLUMN phone;
