-- 冪等寫入（#51）：同一個人用同一個 Idempotency-Key 新增行程，只會真的存一次。
--
-- 為什麼要：按了「加入」之後後端存好了、回應卻沒送到（逾時、502、斷線），畫面只看到失敗，使用者再按一次 ——
-- 後端分不出這是重送還是真的要再加一筆，只能照存，行事曆上就多一筆一樣的。
-- 前端在第一次送之前就替這一次新增取好名字（Idempotency-Key header），重送時原樣再帶，後端看到同一個名字就回原本那幾筆。
--
-- 為什麼用唯一約束、不在程式裡「先查有沒有存過再存」：連點兩下、兩個分頁同時送時，兩邊都會查到「還沒有」然後都存。
-- 主鍵 (owner_id, idempotency_key) 讓資料庫在存的那一刻仲裁：後到的那個 INSERT ... ON CONFLICT DO NOTHING 會等先到的交易結束，
-- 先到的存成功，後到的就插不進去、改回原本那幾筆；先到的失敗 rollback，後到的才接手存。
--
-- 為什麼主鍵帶著 owner_id（跟 V11 同一個想法）：鍵是呼叫端取的名字，不同人取到同一個名字互不相干，
-- 別人也不能拿你的鍵去「重送」、讀到你存的行程。
--
-- 為什麼另開一張表、不在 calendar_event 加一欄：
-- - 一次新增可以有好幾筆（一句話拆出來的），鍵是這一批的名字，不是某一筆的
-- - 鍵只需要留一陣子（見下面），行程要一直留；放在同一列，清鍵就得去改行程
--
-- request_hash：這一批行程內容的 SHA-256（怎麼算在 JdbcCalendarEventAdapter.fingerprint）。
-- 同一個鍵帶了不同的內容就是呼叫端把名字用在兩件事上，回 422，不默默回上次那幾筆（使用者會以為改過的版本存了）。
--
-- event_ids：這一批存了哪幾筆、照什麼順序，重送時照這個順序回原本那幾筆。
-- 不加外鍵：行程被刪掉之後，重送回的就少了那一筆（回的是它們現在的樣子，不會把刪掉的變回來）。
--
-- 保留多久：created_at 超過 calendar.idempotency.retention（預設 1 天）的，由 IdempotencyKeyCleanup 每天清掉。
-- 重送是幾秒到幾分鐘內的事（使用者看到失敗再按一次），一天綽綽有餘；
-- 代價是同一個鍵隔了超過一天才重送，會被當成新的一次再存一份 —— 前端的鍵只活在那一次操作裡，不會隔天才重送。

-- 等鎖、跑太久就放棄，不讓 migration 卡住線上的請求（ci/migrations.sh 的 squawk 規則，同 V12）。
-- 這張表是這支 migration 才建的、裡面沒有資料，實際上不會等；寫上是讓每支 migration 都守同一條規矩
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

-- 長度用 TEXT + CHECK，不用 VARCHAR(n)、CHAR(n)：之後要放寬長度只要換 CHECK，不必改欄位型別（改型別要鎖整張表）
CREATE TABLE calendar_add_request (
    owner_id        UUID        NOT NULL,
    idempotency_key TEXT        NOT NULL CHECK (char_length(idempotency_key) BETWEEN 1 AND 100),
    request_hash    TEXT        NOT NULL CHECK (char_length(request_hash) = 64),
    event_ids       UUID[]      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, idempotency_key)
);

-- 每天的清理用 created_at 找舊的鍵
CREATE INDEX calendar_add_request_created_at ON calendar_add_request (created_at);
