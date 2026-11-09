-- AI 助理發出去的提議卡片（#223）：一張卡片一列，記下它是誰的、後來被採納還是拒絕了。
--
-- 為什麼要存：採納率原本靠前端回報（存檔帶 origin=ASSISTANT、按取消時報張數），伺服器沒辦法核對，
-- 登入的人重複呼叫就能把採納率灌到 0% 以下或 100% 以上。改成伺服器發卡片時給 id、記在這裡，
-- 採納、拒絕都要帶 id，而且只有「這個人的、還沒表態過」的卡片才算一次。
--
-- 只存身分和結果，不存行程內容：內容在回覆裡已經給前端了，使用者還可能改過才存；指標只需要知道「這張卡片後來怎麼了」。
-- 跟其他表一樣不加 owner 的外鍵（見 V11）。舊卡片由每天的清理排程按對話的保留期限刪掉（AssistantMemoryCleanup）。

-- 等鎖、跑太久就放棄，不讓 migration 卡住線上的請求（ci/migrations.sh 的 squawk 規則）。
-- 這張表是這支 migration 才建的、裡面沒有資料，實際上不會等；寫上是讓每支 migration 都守同一條規矩。
-- SET LOCAL：只在這支 migration 的交易裡有效（Flyway 每支包一個交易），不會留在連線上影響之後的查詢
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

CREATE TABLE assistant_proposal (
    id         UUID        PRIMARY KEY,         -- 伺服器發的，前端原樣帶回來
    owner_id   UUID        NOT NULL,
    -- NULL = 還沒表態；表態過就不會再變（更新時帶 outcome IS NULL 條件）
    outcome    TEXT        CHECK (outcome IN ('ADOPTED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    CHECK ((outcome IS NULL) = (decided_at IS NULL))
);

-- 清理排程按發出時間刪
CREATE INDEX assistant_proposal_created_at ON assistant_proposal (created_at);
