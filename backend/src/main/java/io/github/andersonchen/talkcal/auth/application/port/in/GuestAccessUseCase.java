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

    /** 開一個新的訪客帳號。這個方法不檢查每天的名額 */
    GuestPass start();

    /**
     * token 還有效就回訪客帳號，並把期限往後延（最後一次使用起算）；過期、查不到、帳號已經不是訪客都回 empty。
     * 資料庫出錯丟 IllegalStateException。
     */
    Optional<GuestPass> resume(String token);

    /**
     * session 還在、不必用 token 回來的訪客也算在使用：記下最後一次使用的時間，token 的期限跟 resume 一樣往後延，
     * 回傳新的期限；帳號已經不是訪客時回 empty。資料庫出錯丟 IllegalStateException。
     */
    Optional<Instant> keepActive(AccountId guest);

    record GuestPass(AccountId accountId, String token, Instant expiresAt) {
    }
}
