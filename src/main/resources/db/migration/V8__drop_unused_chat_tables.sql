-- 拿掉已經沒有程式在讀寫的三張表。
--
-- conversation、conversation_message（V1）：舊的 Conversation domain 自己存對話用的。
--   conversation 模組改用 Spring AI 之後就沒人碰了，只是一直沒收。
-- spring_ai_chat_memory（V6）：一般問答（/api/chat）的對話紀錄。
--   產品收斂成只有行事曆和它的 AI 助理，/api/chat 整個拿掉，這張表也沒有主人了。
--   助理的記憶在 V7 的 calendar_assistant_message，不受影響。
--
-- 為什麼用新的 migration 刪，而不是回頭改 V1、V6：跑過的 migration 不能改。
-- Flyway 會比對每支檔案的 checksum，改了舊檔，已經跑過的資料庫一啟動就會驗證失敗。
--
-- 不留備份：裡面只有本機學習時的測試對話，沒有要保存的東西。
-- 正式環境刪表之前要先想清楚：資料要不要先匯出？還在跑的舊版程式會不會還在寫？
-- （舊版還在線上的話，要先部署不用這些表的新版，下一次部署才刪表 —— expand/contract 的 contract 那一步。）

-- 先刪子表：conversation_message 有外鍵指向 conversation
DROP TABLE IF EXISTS conversation_message;
DROP TABLE IF EXISTS conversation;
DROP TABLE IF EXISTS spring_ai_chat_memory;
