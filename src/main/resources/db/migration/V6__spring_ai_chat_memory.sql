-- 對話紀錄改由 Spring AI 的 JdbcChatMemoryRepository 管：它只認自己這張表，一則訊息一列。
--
-- 表的長相照抄 Spring AI 2.0.1 附的 schema-postgresql.sql，但由 Flyway 建，不開 Spring AI 自己的
-- spring.ai.chat.memory.repository.jdbc.initialize-schema：資料表的版本只該有一個人管，
-- 兩邊都能建表的話，哪天它的 schema 改了，會變成「哪邊先跑哪邊算」。
-- 升級 Spring AI 時要回頭比對它附的 schema 有沒有變。
--
-- 跟 V1 那兩張表不同：沒有樂觀鎖的 version、也沒有「第幾則」的主鍵。
-- 存檔時 Spring AI 是把整段對話刪掉再全部重插（MessageWindowChatMemory 只留最近 20 則），
-- 同一段對話被同時問兩題，晚存的那題會蓋掉先存的 —— 這是拿掉 domain 之後接受的代價。

CREATE TABLE spring_ai_chat_memory (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL,
    sequence_id     BIGINT      NOT NULL
);

CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");

CREATE INDEX spring_ai_chat_memory_conversation_id_sequence_id_idx
    ON spring_ai_chat_memory (conversation_id, sequence_id);

-- 舊的對話紀錄不搬：格式不同（舊表存的是 domain 的 Conversation），而且只是本機學習資料
DROP TABLE conversation_message;
DROP TABLE conversation;
