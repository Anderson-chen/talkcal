package io.github.andersonchen.talkcal.auth.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity.Provider;

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
        AccountId id = service.signIn(AMY_GOOGLE, "  Amy Chen ", "amy@example.com");

        assertEquals(1, accounts.saved.size());
        Account account = accounts.saved.getFirst();
        assertEquals(id, account.id());
        assertEquals("Amy Chen", account.displayName());
        assertTrue(account.password().isEmpty(), "沒有密碼：不能用帳密登入");
    }

    @Test
    @DisplayName("再登入：同一個帳號，不另外建；名字換了也不蓋掉")
    void laterSignInReusesAccount() {
        AccountId first = service.signIn(AMY_GOOGLE, "Amy Chen", "amy@example.com");

        AccountId second = service.signIn(AMY_GOOGLE, "Amy C.", "amy@example.com");

        assertEquals(first, second);
        assertEquals(1, accounts.saved.size());
        assertEquals("Amy Chen", accounts.saved.getFirst().displayName());
    }

    @Test
    @DisplayName("反例：Google 和 GitHub 上同一個 ID（數字剛好一樣）是不同的人，各自一個帳號")
    void sameSubjectOnAnotherProviderIsAnotherPerson() {
        AccountId google = service.signIn(AMY_GOOGLE, "Amy", null);

        AccountId github = service.signIn(new ExternalIdentity(Provider.GITHUB, AMY_GOOGLE.subject()), null, "amy-gh");

        assertNotEquals(google, github);
        assertEquals("amy-gh", accounts.load(github).orElseThrow().displayName(), "GitHub 沒填名字：用 login");
    }

    @Test
    @DisplayName("兩個分頁同時第一次登入：查的時候還沒有、存的時候另一邊已經存好了 → 用那邊的帳號，不會變成兩個人")
    void concurrentFirstSignInUsesTheWinner() {
        Account winner = Account.external(AccountId.newId(), "Amy（另一個分頁）", null);
        accounts.createdConcurrently = winner;

        AccountId id = service.signIn(AMY_GOOGLE, "Amy", null);

        assertEquals(winner.id(), id);
        assertEquals(2, accounts.identityLookups, "存失敗之後再查一次");
        assertEquals(1, accounts.saved.size(), "這邊建的帳號沒有留下");
    }
}
