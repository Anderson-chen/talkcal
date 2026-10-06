-- 行事曆 AI 助理的對話記憶，連工具呼叫一起存。
--
-- 為什麼不用 V6 那張 spring_ai_chat_memory：Spring AI 的 JdbcChatMemoryRepository 存檔時，
-- 會把「帶工具呼叫的助理訊息」和「工具結果」直接濾掉，每則訊息也只存文字。
-- 對聊天沒差（聊天不用工具），但對 agent 是致命的：下一輪模型看到的歷史變成
-- 「使用者說了時間 → 助理說『已顯示卡片』」，它就學會只說不做（實測：改時間時三次都沒呼叫工具，畫面上沒有卡片）。
-- 歷史裡留著工具呼叫，它才會照著呼叫（同一段對話，三次都正確呼叫）。
--
-- 一則訊息一列，position 是第幾則（從 0 起算）。存檔是整段刪掉重插（跟 Spring AI 的語意一樣），
-- 所以不必樂觀鎖：同一段對話同時兩句，晚存的蓋掉早的 —— 跟聊天那邊接受的代價一樣。

CREATE TABLE calendar_assistant_message (
    conversation_id UUID        NOT NULL,
    position        INTEGER     NOT NULL,
    -- 跟 Spring AI 的 MessageType 名稱一致；system 不存（每一輪由程式重新給，日期表要跟著「現在」走）
    type            TEXT        NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'TOOL')),
    -- 只呼叫工具、沒說話的助理訊息，文字是空字串
    content         TEXT        NOT NULL,
    -- ASSISTANT 呼叫的工具：[{id, type, name, arguments}]；沒呼叫就是 NULL
    tool_calls      JSONB,
    -- TOOL 訊息帶回的結果：[{id, name, responseData}]
    tool_responses  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, position),
    CHECK (type = 'TOOL' OR tool_responses IS NULL),
    CHECK (type = 'ASSISTANT' OR tool_calls IS NULL)
);
