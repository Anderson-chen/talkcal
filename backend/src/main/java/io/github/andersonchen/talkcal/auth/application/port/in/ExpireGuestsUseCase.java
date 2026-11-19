package io.github.andersonchen.talkcal.auth.application.port.in;

import java.time.Instant;

/** 刪掉最後一次使用在 cutoff 之前的訪客帳號，連同它的行程和對話。回傳刪了幾個 */
public interface ExpireGuestsUseCase {

    int expireInactiveSince(Instant cutoff);
}
