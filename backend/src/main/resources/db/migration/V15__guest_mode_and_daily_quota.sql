-- 訪客模式和通用的每日次數限制。
--
-- 訪客也是 account 的一列（tier = 'guest'）：按「以訪客身分試用」時建立，沒有帳號名稱和密碼。
-- 每個 API 照樣要登入，訪客的行程、對話跟會員一樣用 owner_id 分開；註冊時在同一列補上帳號名稱和密碼、改成 free，ID 不變。
--
-- 等鎖或執行太久就放棄，不讓 migration 卡住線上的請求（為什麼用 SET LOCAL 見 V12）。
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';

-- ── 訪客帳號 ─────────────────────────────────────────────────────────

-- V14 的 CHECK 只有 free、paid。換成多一個 guest 的，用 NOT VALID 加：不用掃整張表、不鎖讀取，之後寫入的列照樣檢查。
-- 不另外 VALIDATE：既有的列都通過 V14 那個更嚴的條件（free、paid），一定也符合這個；
-- 在同一個交易裡 VALIDATE 的話，驗證期間整張 account 的讀取都會被擋（squawk 的 constraint-missing-not-valid）
ALTER TABLE account DROP CONSTRAINT account_tier_known;
ALTER TABLE account ADD CONSTRAINT account_tier_known CHECK (tier IN ('guest', 'free', 'paid')) NOT VALID;

-- 訪客最後一次使用的時間。超過期限沒用的訪客帳號由 GuestAccountCleanup 刪掉（連同行程和對話）；會員是 NULL
ALTER TABLE account ADD COLUMN last_active_at TIMESTAMPTZ;

-- 訪客 cookie 裡的 token：同一個瀏覽器關掉再開、session 過期之後，用它回到同一個訪客帳號。
-- 只存 SHA-256，資料庫外洩也拿不到能登入的 token。帳號刪掉（合併、清理、轉成會員時的 DELETE）時一起刪
CREATE TABLE guest_token (
    token_hash TEXT        PRIMARY KEY CHECK (char_length(token_hash) = 64),
    account_id UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX guest_token_account_id ON guest_token (account_id);

-- ── 每日次數限制 ─────────────────────────────────────────────────────
-- 規則放這兩張表，新增一個要限制的 API 只要加一列 quota_rule，不用改程式（app 定時重讀，見 talkcal.quota.rules-refresh）。
-- 計數不在這裡，在 Redis（見 RedisQuotaCounterAdapter）：每個請求都要加一，放資料庫等於每個請求多一次寫入。
--
-- bucket 是一份計數：好幾條規則可以算在同一個 bucket，例如新增、修改、刪除行程合計 10 次。
-- subject 是照什麼分開算：ip（訪客，同一個 IP 的瀏覽器共用）、account（照帳號）、global（全站共用一份）。
-- message 是用完時給使用者看的話。
CREATE TABLE quota_bucket (
    name        TEXT    PRIMARY KEY CHECK (name ~ '^[a-z0-9-]{1,64}$'),
    subject     TEXT    NOT NULL CHECK (subject IN ('ip', 'account', 'global')),
    daily_limit INTEGER NOT NULL CHECK (daily_limit >= 0),
    message     TEXT    NOT NULL CHECK (btrim(message) <> '')
);

-- path_pattern 寫 controller 上的路徑樣板，跟 @RequestMapping 一字不差，{id} 這類變數名稱也要一樣（見 QuotaRule）。
-- tier 是請求者的等級，none 是沒登入。同一個方法、路徑、等級可以算進好幾份計數（例如開訪客帳號同時算全站和那個 IP），
-- 任何一份用完就擋下
CREATE TABLE quota_rule (
    method       TEXT NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    path_pattern TEXT NOT NULL CHECK (path_pattern ~ '^/api/'),
    tier         TEXT NOT NULL CHECK (tier IN ('none', 'guest', 'free', 'paid')),
    bucket       TEXT NOT NULL REFERENCES quota_bucket (name),
    PRIMARY KEY (method, path_pattern, tier, bucket)
);

INSERT INTO quota_bucket (name, subject, daily_limit, message) VALUES
    ('guest-signup',          'global', 30, '今天的訪客名額已經滿了，請註冊帳號使用'),
    -- 同一個 IP 開的訪客帳號：不讓一個人用腳本把全站的名額用完
    ('guest-signup-per-ip',   'ip',      3, '這個網路今天已經開了很多訪客帳號，請註冊帳號使用'),
    ('guest-assistant',       'ip',      3, '今天訪客問助理的次數用完了，註冊後就能繼續使用'),
    ('guest-calendar-writes', 'ip',     10, '今天訪客新增、修改、刪除行程的次數用完了，註冊後就能繼續使用');

INSERT INTO quota_rule (method, path_pattern, tier, bucket) VALUES
    ('POST',   '/api/auth/guest',             'none',  'guest-signup'),
    ('POST',   '/api/auth/guest',             'none',  'guest-signup-per-ip'),
    ('POST',   '/api/calendar/assistant',     'guest', 'guest-assistant'),
    ('POST',   '/api/calendar/events',        'guest', 'guest-calendar-writes'),
    ('PUT',    '/api/calendar/events/{id}',   'guest', 'guest-calendar-writes'),
    ('DELETE', '/api/calendar/events/{id}',   'guest', 'guest-calendar-writes');
