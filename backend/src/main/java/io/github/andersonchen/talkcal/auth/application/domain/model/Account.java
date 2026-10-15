package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 一個用帳密登入的帳號。存的是密碼的雜湊值，不是密碼本身。
 *
 * 雜湊怎麼算（BCrypt）是框架的事，domain 只知道「這是一串雜湊值」，由 HashPasswordPort 的實作算好交進來。
 */
public record Account(AccountId id, Username username, String passwordHash) {

    /**
     * 密碼的長度規則，以 UTF-8 的 bytes 算：
     * - 至少 8：太短的密碼幾秒就猜得到
     * - 最多 72：BCrypt 只看前 72 bytes，後面的會被默默忽略 —— 兩個前 72 bytes 一樣的密碼會被當成同一個。
     *   用 bytes 不用字數：中文一個字是 3 bytes，「24 個中文字」就到上限了
     */
    public static final int MIN_PASSWORD_BYTES = 8;
    public static final int MAX_PASSWORD_BYTES = 72;

    public Account {
        Objects.requireNonNull(id, "帳號 ID 不可為 null");
        Objects.requireNonNull(username, "帳號名稱不可為 null");
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("密碼雜湊不可為空白");
        }
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
