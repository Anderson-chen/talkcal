-- 用帳密登入的帳號（#103）。
--
-- id 是登入之後「目前是誰」的身分（Spring Security 記的就是它），之後行程、對話的擁有者都指向它（#104）。
-- 用 Google、GitHub、Cognito 登入的對應關係另開一張表（#105、#106），不往這張表塞 provider 欄位：
-- 一個人可以同時綁好幾種登入方式，一張表一個欄位放不下。
--
-- 只存密碼的雜湊（BCrypt，雜湊值裡已經帶著 salt 和 cost），不存密碼本身。

CREATE TABLE account (
    id            UUID        PRIMARY KEY,
    -- 規則跟 Username 一樣（小寫、3～32 個字、英數底線點減號）。程式已經擋過，資料庫再擋一次：
    -- 之後有人直接下 SQL 或寫錯程式，也塞不進不合規的名字
    username      TEXT        NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_.-]{3,32}$'),
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
