package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.UUID;

/**
 * 誰的行程、誰的對話。每個查詢、新增、刪除都限定在一個擁有者裡。
 *
 * 不用 auth 模組的 AccountId：兩個模組互不認識（ArchitectureTest 守著）。值就是登入者的帳號 ID，
 * 但 calendar 不知道它是怎麼登入的。
 */
public record OwnerId(UUID value) {

    public OwnerId {
        if (value == null) {
            throw new IllegalArgumentException("擁有者 ID 不可為 null");
        }
    }

    /** 登入者的名字就是帳號 ID。格式不對丟 IllegalArgumentException。 */
    public static OwnerId of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("擁有者 ID 不可為 null 或空白");
        }
        try {
            return new OwnerId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("擁有者 ID 格式不對：" + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
