-- 用 Google、GitHub 帳號登入（#105）。
--
-- 這樣登入的人也是 account 表裡的一列（行程、對話的擁有者一樣指向 account.id），只是沒有帳號名稱和密碼：
-- 他從沒在這裡設過密碼，給他一個用不到的密碼只會多一個可以被猜的入口。

-- 給人看的名字。帳密登入的就是帳號名稱；Google、GitHub 登入的取對方給的名字。
-- 先加成可以是 NULL、補上既有帳號的值，再改成 NOT NULL：直接加 NOT NULL 的話，表裡已經有的帳號會讓這一行失敗
ALTER TABLE account ADD COLUMN display_name TEXT;
UPDATE account SET display_name = username;
ALTER TABLE account ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE account ADD CONSTRAINT account_display_name_not_blank CHECK (btrim(display_name) <> '');

-- 帳號名稱和密碼雜湊要嘛都有（帳密登入）、要嘛都沒有（只用 Google、GitHub 登入），不會只有一個。
-- UNIQUE 不受影響：PostgreSQL 的 UNIQUE 允許好幾列都是 NULL
ALTER TABLE account ALTER COLUMN username DROP NOT NULL;
ALTER TABLE account ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE account ADD CONSTRAINT account_password_login_complete
    CHECK ((username IS NULL) = (password_hash IS NULL));

-- 「Google 的某個人」是我們的哪個帳號。
-- 用 provider 給的使用者 ID（Google 的 sub、GitHub 的數字 id）對應，不用 email 或 GitHub 帳號名稱：
-- 後兩者使用者自己改得掉，改了之後會變成另一個人；也不靠 email 把 Google 和 GitHub 自動合併成同一個帳號 ——
-- 有人在 GitHub 填了你的 email（GitHub 不一定驗過），就能登進你的帳號。
-- 另開一張表而不是在 account 上加欄位：一個帳號之後可以綁好幾種登入方式。
CREATE TABLE account_identity (
    provider    TEXT        NOT NULL CHECK (provider IN ('google', 'github')),
    subject     TEXT        NOT NULL CHECK (subject <> ''),
    -- 帳號刪掉時，它的登入方式跟著刪：留著的話，下次用同一個 Google 帳號登入會指到不存在的帳號
    account_id  UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一個 Google 帳號只能對到一個我們的帳號；兩個分頁同時第一次登入時，靠它只建出一個
    PRIMARY KEY (provider, subject)
);

-- 查「這個帳號綁了哪些登入方式」用（例如之後要刪帳號、解除綁定）
CREATE INDEX account_identity_account_id ON account_identity (account_id);
