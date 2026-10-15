package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.util.UUID;

/**
 * 一個帳號的身分。登入之後，Spring Security 記住的「目前是誰」就是它（不是帳號名稱）。
 *
 * 為什麼用它、不用帳號名稱當身分：
 * - 帳號名稱之後可能開放改名；身分一改，所有屬於這個人的資料（行程、對話）都要跟著搬
 * - 用 Google、GitHub 登入的人沒有自己取的帳號名稱，但一樣要有一個身分（#105）
 * - 授權（#104）比對「這筆資料是不是你的」就是比這個值
 *
 * 用 UUID 不用流水號：跟 calendar 的 EventId 同一個理由 —— 流水號會洩漏總共有幾個帳號，也猜得到別人的編號。
 */
public record AccountId(UUID value) {

    public AccountId {
        if (value == null) {
            throw new IllegalArgumentException("帳號 ID 不可為 null");
        }
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }

    /** 從外部收到的字串（例如 session 裡記的身分）。格式不對丟 IllegalArgumentException。 */
    public static AccountId of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("帳號 ID 不可為 null 或空白");
        }
        try {
            return new AccountId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("帳號 ID 格式不對：" + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
