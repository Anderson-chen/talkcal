package io.github.andersonchen.talkcal.auth.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity.Provider;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignInExternallyService")
class SignInExternallyServiceTest {

    private static final ExternalIdentity AMY_GOOGLE = new ExternalIdentity(Provider.GOOGLE, "1234567890");

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final SignInExternallyService service = new SignInExternallyService(accounts, accounts);

    @Test
    @DisplayName("第一次登入：建一個沒有帳號名稱和密碼的帳號，顯示名稱是對方給的名字")
    void firstSignInCreatesAccount() {
        AccountId id = service.signIn(null, AMY_GOOGLE, "  Amy Chen ", "amy@example.com");

        assertEquals(1, accounts.saved.size());
        Account account = accounts.saved.getFirst();
        assertEquals(id, account.id());
        assertEquals("Amy Chen", account.displayName());
        assertTrue(account.password().isEmpty(), "沒有密碼：不能用帳密登入");
    }

    @Test
    @DisplayName("再登入：同一個帳號，不另外建；名字換了也不蓋掉")
    void laterSignInReusesAccount() {
        AccountId first = service.signIn(null, AMY_GOOGLE, "Amy Chen", "amy@example.com");

        AccountId second = service.signIn(null, AMY_GOOGLE, "Amy C.", "amy@example.com");

        assertEquals(first, second);
        assertEquals(1, accounts.saved.size());
        assertEquals("Amy Chen", accounts.saved.getFirst().displayName());
    }

    @Test
    @DisplayName("反例：Google 和 GitHub 上同一個 ID（數字剛好一樣）是不同的人，各自一個帳號")
    void sameSubjectOnAnotherProviderIsAnotherPerson() {
        AccountId google = service.signIn(null, AMY_GOOGLE, "Amy", null);

        AccountId github = service.signIn(null, new ExternalIdentity(Provider.GITHUB, AMY_GOOGLE.subject()), null, "amy-gh");

        assertNotEquals(google, github);
        assertEquals("amy-gh", accounts.load(github).orElseThrow().displayName(), "GitHub 沒填名字：用 login");
    }

    @Test
    @DisplayName("兩個分頁同時第一次登入：查的時候還沒有、存的時候另一邊已經存好了 → 用那邊的帳號，不會變成兩個人")
    void concurrentFirstSignInUsesTheWinner() {
        Account winner = Account.external(AccountId.newId(), "Amy（另一個分頁）", null);
        accounts.createdConcurrently = winner;

        AccountId id = service.signIn(null, AMY_GOOGLE, "Amy", null);

        assertEquals(winner.id(), id);
        assertEquals(2, accounts.identityLookups, "存失敗之後再查一次");
        assertEquals(1, accounts.saved.size(), "這邊建的帳號沒有留下");
    }

    // ── 訪客用 Google、GitHub 註冊 ─────────────────────────────────────

    private AccountId guest() {
        AccountId id = AccountId.newId();
        Instant now = Instant.now();
        accounts.saveGuest(Account.guest(id), "a".repeat(64), now, now.plusSeconds(60));
        return id;
    }

    @Test
    @DisplayName("訪客第一次用 Google 登入：接到同一個訪客帳號，升級成免費會員，顯示名稱是 Google 給的；訪客 token 刪掉")
    void guestUpgradesInPlace() {
        AccountId guest = guest();

        AccountId id = service.signIn(guest, AMY_GOOGLE, "Amy Chen", null);

        assertEquals(guest, id, "帳號 ID 不變，行程和對話不用搬");
        assertEquals(1, accounts.saved.size(), "沒有另外開帳號");
        Account account = accounts.load(guest).orElseThrow();
        assertEquals(MembershipTier.FREE, account.tier());
        assertEquals("Amy Chen", account.displayName());
        assertEquals(guest, accounts.load(AMY_GOOGLE).orElseThrow().id(), "之後用同一個 Google 帳號登入，就是這個帳號");
        assertTrue(accounts.tokens.isEmpty(), "不是訪客了，訪客 cookie 不能再登入這個帳號");
    }

    @Test
    @DisplayName("反例：訪客用已經有帳號的 Google 登入：登進那個帳號，訪客帳號不動（資料由 MergeGuestUseCase 搬）")
    void guestSigningIntoExistingIdentityKeepsThatAccount() {
        AccountId existing = service.signIn(null, AMY_GOOGLE, "Amy", null);
        AccountId guest = guest();

        AccountId id = service.signIn(guest, AMY_GOOGLE, "Amy", null);

        assertEquals(existing, id);
        assertEquals(MembershipTier.GUEST, accounts.load(guest).orElseThrow().tier());
    }

    @Test
    @DisplayName("反例：已登入的是會員（不是訪客）：Google 照舊另開一個帳號，不接到會員的帳號上")
    void memberIsNotUpgraded() {
        Account member = Account.withPassword(AccountId.newId(), Username.of("bob"), "hash");
        accounts.saveIfUsernameFree(member);

        AccountId id = service.signIn(member.id(), AMY_GOOGLE, "Amy", null);

        assertNotEquals(member.id(), id);
        assertEquals("bob", accounts.load(member.id()).orElseThrow().displayName());
        assertEquals(id, accounts.load(AMY_GOOGLE).orElseThrow().id());
    }
}
