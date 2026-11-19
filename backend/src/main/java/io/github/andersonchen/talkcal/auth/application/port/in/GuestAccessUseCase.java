package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.time.Instant;
import java.util.Optional;

/**
 * 「以訪客身分試用」：開一個訪客帳號，發一個 token 讓同一個瀏覽器之後回來還是同一個訪客。
 *
 * token 只在 GuestPass 裡出現這一次，存起來的是雜湊：資料庫外洩也拿不到能登入的 token。
 */
public interface GuestAccessUseCase {

    /** 開一個新的訪客帳號。每天的名額由限流擋在前面，這裡不管 */
    GuestPass start();

    /**
     * token 還有效就回訪客帳號，並把期限往後延（最後一次使用起算）；過期、查不到、帳號已經不是訪客都回 empty。
     * 資料庫出錯丟 IllegalStateException。
     */
    Optional<GuestPass> resume(String token);

    record GuestPass(AccountId accountId, String token, Instant expiresAt) {
    }
}
