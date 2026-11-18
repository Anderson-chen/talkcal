package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 同一個帳號可以用帳密、Google、GitHub 其中幾種登入：
 * - 帳密：passwordLogin 有值
 * - Google、GitHub：記在另一張表（ExternalIdentity 對到這個帳號）；只用這種方式登入的帳號 passwordLogin 是 null
 *
 * displayName 是給人看的名字，可能重複、可能改，不能當身分；身分是 id。
 *
 * 新帳號都是免費會員；改成付費要由維護者直接改資料庫（見 V14）。
 */
public record Account(AccountId id, String displayName, PasswordLogin passwordLogin, MembershipTier tier) {

    /**
     * 以 UTF-8 的 bytes 算：
     * - 至少 8：太短的密碼幾秒就猜得到
     * - 最多 72：BCrypt 只看前 72 bytes，後面的直接忽略、不報錯，前 72 bytes 一樣的兩個密碼會被當成同一個。
     *   中文一個字是 3 bytes，24 個中文字就到上限
     */
    public static final int MIN_PASSWORD_BYTES = 8;
    public static final int MAX_PASSWORD_BYTES = 72;

    /** 畫面右上角放得下，也不讓 provider 給的超長名字存進資料庫。 */
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /** 存的是 HashPasswordPort 算好的雜湊值，domain 裡沒有演算法的細節。 */
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
        Objects.requireNonNull(tier, "會員等級不可為 null");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("顯示名稱不可為空白");
        }
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("顯示名稱最多 " + MAX_DISPLAY_NAME_LENGTH + " 個字");
        }
    }

    public static Account withPassword(AccountId id, Username username, String passwordHash) {
        return new Account(id, username.value(), new PasswordLogin(username, passwordHash), MembershipTier.FREE);
    }

    /**
     * Google、GitHub 第一次登入時建的帳號，沒有帳號名稱和密碼：這個人從沒在這裡設過密碼，
     * 給他一個用不到的密碼只會多一個能被猜的入口。
     *
     * 對方給的名字不一定合規則：去頭尾空白、太長截掉；整理完是空的（GitHub 沒填名字）就用 fallback。
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
        return new Account(id, cleaned, null, MembershipTier.FREE);
    }

    public Optional<PasswordLogin> password() {
        return Optional.ofNullable(passwordLogin);
    }

    /** 不合規丟 IllegalArgumentException，訊息直接給使用者看。 */
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
