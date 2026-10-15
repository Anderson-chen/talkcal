package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 一個帳號。登入的方式有兩種，同一個帳號可以有其中一種或兩種：
 * - 帳密：passwordLogin 有值（帳號名稱＋密碼的雜湊）
 * - Google、GitHub：記在另一張表（ExternalIdentity 對到這個帳號），只用這種方式登入的帳號 passwordLogin 是 null
 *
 * displayName 是給人看的名字：帳密登入的就是帳號名稱，Google、GitHub 登入的取對方給的名字。
 * 它不是身分（可能重複、可能改），身分是 id。
 */
public record Account(AccountId id, String displayName, PasswordLogin passwordLogin) {

    /**
     * 密碼的長度規則，以 UTF-8 的 bytes 算：
     * - 至少 8：太短的密碼幾秒就猜得到
     * - 最多 72：BCrypt 只看前 72 bytes，後面的會被默默忽略 —— 兩個前 72 bytes 一樣的密碼會被當成同一個。
     *   用 bytes 不用字數：中文一個字是 3 bytes，「24 個中文字」就到上限了
     */
    public static final int MIN_PASSWORD_BYTES = 8;
    public static final int MAX_PASSWORD_BYTES = 72;

    /** 顯示名稱最多幾個字：畫面右上角放得下，也不讓對方給的超長名字灌進資料庫。 */
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /**
     * 帳密登入要的兩樣東西。存的是密碼的雜湊值，不是密碼本身。
     *
     * 雜湊怎麼算（BCrypt）是框架的事，domain 只知道「這是一串雜湊值」，由 HashPasswordPort 的實作算好交進來。
     */
    public record PasswordLogin(Username username, String passwordHash) {

        public PasswordLogin {
            Objects.requireNonNull(username, "帳號名稱不可為 null");
            if (passwordHash == null || passwordHash.isBlank()) {
                throw new IllegalArgumentException("密碼雜湊不可為空白");
            }
        }
    }

    public Account {
        Objects.requireNonNull(id, "帳號 ID 不可為 null");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("顯示名稱不可為空白");
        }
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("顯示名稱最多 " + MAX_DISPLAY_NAME_LENGTH + " 個字");
        }
    }

    /** 用帳密註冊的帳號：顯示名稱就是帳號名稱。 */
    public static Account withPassword(AccountId id, Username username, String passwordHash) {
        return new Account(id, username.value(), new PasswordLogin(username, passwordHash));
    }

    /**
     * 用 Google、GitHub 第一次登入時建的帳號：沒有帳號名稱和密碼 ——
     * 他從沒在這裡設過密碼，給他一個用不到的密碼只會多一個可以被猜的入口。
     *
     * 名字是對方給的，不一定合我們的規則，所以先整理：去頭尾空白、太長截掉；整理完是空的（GitHub 沒填名字）就用 fallback。
     */
    public static Account external(AccountId id, String name, String fallback) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) {
            cleaned = fallback == null ? "" : fallback.strip();
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_DISPLAY_NAME_LENGTH) {
            // 用 code point 算：以 char 截的話，emoji 這類兩個 char 的字可能被切成一半
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_DISPLAY_NAME_LENGTH));
        }
        return new Account(id, cleaned, null);
    }

    /** 帳密登入的資料；只用 Google、GitHub 登入的帳號是 empty。 */
    public Optional<PasswordLogin> password() {
        return Optional.ofNullable(passwordLogin);
    }

    /** 新密碼合不合規則；不合規丟 IllegalArgumentException，訊息直接給使用者看。 */
    public static void checkNewPassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("請輸入密碼");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_PASSWORD_BYTES) {
            throw new IllegalArgumentException("密碼至少要 " + MIN_PASSWORD_BYTES + " 個英數字");
        }
        if (bytes > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("密碼太長了（最多 " + MAX_PASSWORD_BYTES + " 個英數字，中文一個字算 3 個）");
        }
    }
}
