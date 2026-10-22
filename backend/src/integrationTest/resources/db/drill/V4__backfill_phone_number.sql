-- R3 第 2 支：把舊資料的 phone 抄到 phone_number。
--
-- 條件是「不一致」而不只是「NULL」：R2 期間 v1 還在線上，它改電話只改 phone，
-- v2 雙寫過的那筆 phone_number 就停在舊值。這時 phone 才是對的（v1、v2 都寫它），所以以 phone 為準整個蓋過去。
-- 只補 NULL 的話，那些被 v1 改過的會留著舊電話，v3 一切讀就讀錯（測試裡有這個反例）。
-- 跑的時候 v2、v3 也在寫，但它們兩邊寫一樣的值，不會跟這句打架。
--
-- 演練的資料量一句就好。正式的大表要分批（每批幾千列、各自 commit），一句大 UPDATE 會長時間佔著列鎖、產生大量 WAL、拖慢複寫。

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';

UPDATE drill_contact
SET phone_number = phone
WHERE phone_number IS DISTINCT FROM phone;
