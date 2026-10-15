package io.github.andersonchen.talkcal.auth.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("帳號的規則：帳號名稱、密碼長度、顯示名稱")
class AccountRulesTest {

    @Test
    @DisplayName("帳號名稱去掉頭尾空白、轉小寫：「 Amy 」和「amy」是同一個帳號")
    void usernameIsNormalised() {
        assertEquals(new Username("amy"), Username.of("  Amy  "));
    }

    @ParameterizedTest(name = "「{0}」不收")
    @ValueSource(strings = {"ab", "a-very-long-username-that-is-over-32", "有中文", "has space", "ａｍｙ（全形）", ""})
    @DisplayName("太短、太長、中文、空白、全形字：不收")
    void badUsernames(String name) {
        assertThrows(IllegalArgumentException.class, () -> Username.of(name));
    }

    @ParameterizedTest(name = "「{0}」可以")
    @ValueSource(strings = {"amy", "amy.chen", "amy_2026", "a-b", "abcdefghijklmnopqrstuvwxyz012345"})
    @DisplayName("反例：英數、底線、點、減號，3～32 個字都可以")
    void goodUsernames(String name) {
        assertDoesNotThrow(() -> Username.of(name));
    }

    @Test
    @DisplayName("密碼：8 bytes 以上才收")
    void passwordTooShort() {
        assertThrows(IllegalArgumentException.class, () -> Account.checkNewPassword("1234567"));
        assertDoesNotThrow(() -> Account.checkNewPassword("12345678"));
    }

    @Test
    @DisplayName("密碼：最多 72 bytes（BCrypt 只看前 72 bytes），中文一個字算 3 bytes")
    void passwordTooLong() {
        assertDoesNotThrow(() -> Account.checkNewPassword("a".repeat(72)));
        assertThrows(IllegalArgumentException.class, () -> Account.checkNewPassword("a".repeat(73)));
        // 24 個中文字剛好 72 bytes；25 個就超過 —— 照「字數」算的話會以為 25 個字沒問題
        assertDoesNotThrow(() -> Account.checkNewPassword("密".repeat(24)));
        assertThrows(IllegalArgumentException.class, () -> Account.checkNewPassword("密".repeat(25)));
    }

    @Test
    @DisplayName("Google、GitHub 給的名字：去頭尾空白；空的（GitHub 沒填 name）用 fallback")
    void externalNameIsCleaned() {
        assertEquals("Amy Chen", Account.external(AccountId.newId(), "  Amy Chen\n", "amy").displayName());
        assertEquals("amy-gh", Account.external(AccountId.newId(), null, "amy-gh").displayName());
        assertEquals("amy-gh", Account.external(AccountId.newId(), "   ", "amy-gh").displayName());
    }

    @Test
    @DisplayName("名字太長：截到 64 個字，emoji 不會被切成一半")
    void externalNameIsTruncatedByCodePoint() {
        String name = Account.external(AccountId.newId(), "😀".repeat(100), null).displayName();

        assertEquals(Account.MAX_DISPLAY_NAME_LENGTH, name.codePointCount(0, name.length()));
        assertEquals("😀".repeat(Account.MAX_DISPLAY_NAME_LENGTH), name);
    }

    @Test
    @DisplayName("名字和 fallback 都是空的：不建帳號（顯示名稱不可空白）")
    void externalNameAndFallbackBlank() {
        assertThrows(IllegalArgumentException.class, () -> Account.external(AccountId.newId(), " ", null));
    }

    @Test
    @DisplayName("帳密註冊的帳號：顯示名稱就是帳號名稱；Google、GitHub 建的沒有密碼")
    void passwordLoginOnlyForPasswordAccounts() {
        Account local = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}x");
        assertEquals("amy", local.displayName());
        assertEquals(new Username("amy"), local.password().orElseThrow().username());

        assertEquals(java.util.Optional.empty(), Account.external(AccountId.newId(), "Amy", null).password());
    }
}
