package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;

/**
 * 同一個冪等鍵帶了不一樣的行程（#51）：這不是重送，是呼叫端把名字用在兩件不同的事上。
 *
 * 不默默回上一次存的那幾筆：使用者這次送的內容沒被存，卻會以為存了。
 * 也不當成新的一次存起來：那樣同一個名字就對應到兩次新增，「重送不會多一筆」的保證就破了。
 * 所以直接拒絕，HTTP 那頭翻成 422，呼叫端該換一個新的鍵再送。
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(IdempotencyKey key) {
        super("Idempotency-Key 已經用在另一批不同的行程上：" + key + "；內容改過就換一個新的鍵");
    }
}
