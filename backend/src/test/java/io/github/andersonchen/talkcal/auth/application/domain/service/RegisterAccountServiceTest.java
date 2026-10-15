package io.github.andersonchen.talkcal.auth.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.in.UsernameTakenException;
import io.github.andersonchen.talkcal.auth.application.port.out.HashPasswordPort;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegisterAccountService")
class RegisterAccountServiceTest {

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final List<Account> saved = accounts.saved;
    private final List<String> hashed = new ArrayList<>();
    // 假的雜湊：記下被算過哪些密碼，好檢查「規則沒過就不算雜湊」
    private final HashPasswordPort hasher = raw -> {
        hashed.add(raw);
        return "hash(" + raw + ")";
    };

    private RegisterAccountService service() {
        return new RegisterAccountService(accounts, hasher);
    }

    @Test
    @DisplayName("註冊：帳號名稱轉小寫、存的是雜湊不是密碼本身，回傳新帳號的 ID")
    void registers() {
        AccountId id = service().register("  Amy ", "correct-horse");

        assertEquals(1, saved.size());
        Account account = saved.getFirst();
        assertEquals(id, account.id());
        assertEquals(new Username("amy"), account.password().orElseThrow().username());
        assertEquals("hash(correct-horse)", account.password().orElseThrow().passwordHash());
        assertEquals("amy", account.displayName(), "帳密註冊的顯示名稱就是帳號名稱");
    }

    @Test
    @DisplayName("帳號名稱已經有人用了（資料庫說撞名）：UsernameTakenException")
    void usernameTaken() {
        service().register("amy", "correct-horse");

        assertThrows(UsernameTakenException.class, () -> service().register("AMY", "another-horse"));
        assertEquals(1, saved.size());
    }

    @Test
    @DisplayName("密碼不合規則：丟 IllegalArgumentException，而且根本不算雜湊、不存（BCrypt 很慢，不合規的不必算）")
    void badPasswordIsRejectedBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> service().register("amy", "short"));

        assertTrue(hashed.isEmpty());
        assertTrue(saved.isEmpty());
    }

    @Test
    @DisplayName("帳號名稱不合規則：一樣先擋下，不算雜湊")
    void badUsernameIsRejectedBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> service().register("有中文", "correct-horse"));

        assertTrue(hashed.isEmpty());
    }
}
