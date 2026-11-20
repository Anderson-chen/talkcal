package io.github.andersonchen.talkcal.quota.application.domain.model;

import java.util.Objects;

/** 一份每日計數。好幾條 QuotaRule 可以算在同一份，例如新增、修改、刪除行程合計 */
public record QuotaBucket(String name, QuotaSubject subject, int dailyLimit, String message) {

    public QuotaBucket {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("計數名稱不可為空白");
        }
        Objects.requireNonNull(subject, "計數方式不可為 null");
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("每天的上限不可為負：" + dailyLimit);
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("用完時的說明不可為空白");
        }
    }
}
