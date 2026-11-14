package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.util.UUID;

/**
 * 帳號的身分，登入之後的「目前是誰」就是這個 ID。不用帳號名稱當身分：
 * - 帳號名稱之後可能開放改名，身分一改，這個人的行程、對話都要跟著搬
 * - 用 Google、GitHub 登入的人沒有帳號名稱，一樣要有身分
 *
 * 用 UUID 不用流水號：流水號會洩漏總共有幾個帳號，也猜得到別人的編號。
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

    /** 例如 session 裡記的身分。格式不對丟 IllegalArgumentException。 */
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
