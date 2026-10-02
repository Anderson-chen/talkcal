-- 對話紀錄：一段對話（aggregate root）一列，它的訊息另一張表。
-- 這兩張表永遠一起寫（JdbcConversationAdapter.save 包在一個交易裡），對應 domain 的一個 Conversation。

CREATE TABLE conversation (
    id          UUID        PRIMARY KEY,           -- ConversationId：domain 自己發的 UUID，不靠資料庫流水號
    instruction TEXT,                               -- 系統指令，沒有就是 NULL
    version     BIGINT      NOT NULL,               -- 樂觀鎖：存過幾次。存檔時 WHERE version = 讀到的那版，對不上就是有人先存了
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_message (
    conversation_id UUID    NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    -- 第幾則（從 0 起算）。歷史只能追加（規則 3），所以存檔只要插入「比資料庫裡多出來的那幾則」
    position        INTEGER NOT NULL,
    -- USER / ASSISTANT，跟 Conversation.Role 一致。CHECK 擋掉手動塞進來的怪值，
    -- 但真正的規則（交替、不可空白）在 Conversation.restore 讀出來時還會再檢查一次
    role            TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text            TEXT    NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, position)
);
