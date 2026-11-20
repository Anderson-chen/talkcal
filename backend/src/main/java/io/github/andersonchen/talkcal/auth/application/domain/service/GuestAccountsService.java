package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.ExpireGuestsUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.FinishGuestMergesUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.GuestStorePort;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.OwnedDataPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 訪客帳號從開到結束：開帳號、用 token 回來、登入別的帳號時併過去（過一段時間再搬一次）、太久沒用就刪掉。
 * 註冊成正式會員（同一個帳號升級）在 RegisterAccountService。
 */
public final class GuestAccountsService
        implements GuestAccessUseCase, MergeGuestUseCase, FinishGuestMergesUseCase, ExpireGuestsUseCase {

    // 32 bytes 的亂數，猜中的機率可以忽略；token 是登入憑證，一定要用 SecureRandom
    private static final int TOKEN_BYTES = 32;

    private final GuestStorePort guests;
    private final LoadAccountPort accounts;
    private final OwnedDataPort ownedData;
    private final Duration lifetime;
    private final Duration finishMergeAfter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** finishMergeAfter：合併之後隔多久再搬一次（見 FinishGuestMergesUseCase） */
    public GuestAccountsService(GuestStorePort guests, LoadAccountPort accounts, OwnedDataPort ownedData,
                                Duration lifetime, Duration finishMergeAfter, Clock clock) {
        this.guests = Objects.requireNonNull(guests, "guests 不可為 null");
        this.accounts = Objects.requireNonNull(accounts, "accounts 不可為 null");
        this.ownedData = Objects.requireNonNull(ownedData, "ownedData 不可為 null");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime 不可為 null");
        this.finishMergeAfter = Objects.requireNonNull(finishMergeAfter, "finishMergeAfter 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        if (lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("訪客 token 的期限要大於 0：" + lifetime);
        }
        if (finishMergeAfter.isNegative() || finishMergeAfter.isZero()) {
            // 0 的話合併完馬上就再搬，還在處理中的請求之後存的照樣留在訪客名下
            throw new IllegalArgumentException("合併之後再搬一次的間隔要大於 0：" + finishMergeAfter);
        }
    }

    @Override
    public GuestPass start() {
        Account guest = Account.guest(AccountId.newId());
        String token = newToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        guests.saveGuest(guest, hash(token), now, expiresAt);
        return new GuestPass(guest.id(), token, expiresAt);
    }

    @Override
    public Optional<GuestPass> resume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        return guests.touch(hash(token), now, expiresAt).map(id -> new GuestPass(id, token, expiresAt));
    }

    @Override
    public Optional<Instant> keepActive(AccountId guest) {
        Objects.requireNonNull(guest, "guest 不可為 null");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        return guests.keepActive(guest, now, expiresAt) ? Optional.of(expiresAt) : Optional.empty();
    }

    @Override
    public void mergeInto(AccountId guest, AccountId target) {
        Objects.requireNonNull(guest, "guest 不可為 null");
        Objects.requireNonNull(target, "target 不可為 null");
        if (guest.equals(target)) {
            return;
        }
        // 已經不是訪客：可能是另一個分頁先註冊成正式會員了，那是別人的資料，不能搬
        if (accounts.load(guest).filter(Account::isGuest).isEmpty()) {
            return;
        }
        ownedData.transfer(guest, target);
        guests.deleteMerged(guest, target, clock.instant());
    }

    @Override
    public int finishDue() {
        int finished = 0;
        List<IllegalStateException> failures = new ArrayList<>();
        for (GuestStorePort.Merge merge : guests.mergedBefore(clock.instant().minus(finishMergeAfter))) {
            try {
                // 搬完才刪紀錄：中途失敗的話紀錄還在，下一次再搬剩下的
                ownedData.transfer(merge.guest(), merge.target());
                guests.forgetMerge(merge.guest());
                finished++;
            } catch (IllegalStateException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failed = new IllegalStateException(
                    "訪客合併之後再搬一次：" + failures.size() + " 筆失敗，紀錄留著下一次再搬；" + finished + " 筆搬完");
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
        return finished;
    }

    @Override
    public int expireInactive() {
        List<AccountId> expired = guests.guestsInactiveSince(clock.instant().minus(lifetime));
        for (AccountId guest : expired) {
            // 先刪資料再刪帳號：中途失敗的話帳號還在，下一次排程會再刪一次
            ownedData.deleteAll(guest);
            guests.delete(guest);
        }
        return expired.size();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // token 本身已經是高熵的亂數，不需要 BCrypt 這種刻意算很慢的雜湊；SHA-256 讓資料庫外洩時拿不到能用的 token
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 沒有 SHA-256", e);
        }
    }
}
