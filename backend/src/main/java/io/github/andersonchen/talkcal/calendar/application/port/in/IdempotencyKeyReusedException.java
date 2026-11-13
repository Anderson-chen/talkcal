package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;

/**
 * 同一個冪等鍵帶了不一樣的行程：不是重送，是呼叫端把名字用在兩件事上。HTTP 那頭翻成 422，呼叫端該換一個鍵。
 *
 * 不默默回上次存的那幾筆：使用者這次送的內容沒存，卻會以為存了。
 * 也不當成新的一次存起來：同一個名字對應到兩次新增，重送不會多一筆的保證就破了。
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(IdempotencyKey key) {
        super("Idempotency-Key 已經用在另一批不同的行程上：" + key + "；內容改過就換一個新的鍵");
    }
}
