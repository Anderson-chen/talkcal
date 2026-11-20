package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 訪客帳號和它的 token。tokenHash 是 token 的 SHA-256（hex），不存 token 本身。資料庫出錯丟 IllegalStateException */
public interface GuestStorePort {

    /** 帳號和 token 一起存，一起成功或一起不算；now 記成最後一次使用的時間 */
    void saveGuest(Account guest, String tokenHash, Instant now, Instant expiresAt);

    /**
     * token 存在、還沒過期、帳號還是訪客，就把期限改成 newExpiresAt、記下最後一次使用的時間，回傳帳號；否則 empty。
     * 檢查和延期在同一句 SQL 完成。
     */
    Optional<AccountId> touch(String tokenHash, Instant now, Instant newExpiresAt);

    /** 帳號還是訪客，就記下最後一次使用的時間、把還沒過期的 token 期限改成 newExpiresAt，回 true；否則 false */
    boolean keepActive(AccountId guest, Instant now, Instant newExpiresAt);

    /** 最後一次使用在 cutoff 之前的訪客帳號 */
    List<AccountId> guestsInactiveSince(Instant cutoff);

    /** 刪掉帳號，token 跟著刪（外鍵 ON DELETE CASCADE） */
    void delete(AccountId account);
}
