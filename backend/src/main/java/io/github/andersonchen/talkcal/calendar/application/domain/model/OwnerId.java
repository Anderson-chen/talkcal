package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.UUID;

/**
 * 行事曆的擁有者：誰的行程、誰的對話。每個查詢、新增、刪除都限定在一個擁有者裡（#104）。
 *
 * calendar 自己的型別，不用 auth 模組的 AccountId：兩個模組互不認識（ArchitectureTest 守著）。
 * 值其實就是登入者的帳號 ID，但 calendar 只知道「這是一個擁有者」，不知道它是怎麼登入、從哪裡來的 ——
 * 由 inbound adapter（HTTP controller）從登入者取出來交給 use case。
 */
public record OwnerId(UUID value) {

    public OwnerId {
        if (value == null) {
            throw new IllegalArgumentException("擁有者 ID 不可為 null");
        }
    }

    /**
     * 從外部收到的字串（登入者的名字，就是帳號 ID）。格式不對丟 IllegalArgumentException。
     */
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
