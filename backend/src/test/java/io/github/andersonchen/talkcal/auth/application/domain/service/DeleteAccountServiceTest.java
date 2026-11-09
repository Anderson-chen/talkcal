package io.github.andersonchen.talkcal.auth.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeleteAccountService")
class DeleteAccountServiceTest {

    private static final AccountId AMY = AccountId.newId();

    // 兩個 port 依序記下被叫了什麼，看順序
    private final List<String> calls = new ArrayList<>();

    @Test
    @DisplayName("先請其他模組刪資料，最後才刪帳號")
    void forgetsDataBeforeDeletingAccount() {
        new DeleteAccountService(id -> calls.add("forget " + id), id -> calls.add("delete " + id)).delete(AMY);

        assertEquals(List.of("forget " + AMY, "delete " + AMY), calls);
    }

    @Test
    @DisplayName("刪資料失敗：帳號不刪 —— 留下的是「帳號還在」，使用者可以再要求一次；反例見上一題")
    void keepsAccountWhenDataCannotBeErased() {
        DeleteAccountService service = new DeleteAccountService(
                id -> {
                    throw new IllegalStateException("資料庫斷線");
                },
                id -> calls.add("delete " + id));

        assertThrows(IllegalStateException.class, () -> service.delete(AMY));
        assertEquals(List.of(), calls);
    }

    @Test
    @DisplayName("帳號已經不在（另一個分頁先刪了）不算錯：資料照樣再刪一次")
    void accountAlreadyGoneIsFine() {
        new DeleteAccountService(id -> calls.add("forget " + id), id -> false).delete(AMY);

        assertEquals(List.of("forget " + AMY), calls);
    }

    @Test
    @DisplayName("id 是 null：IllegalArgumentException，什麼都不刪")
    void rejectsNull() {
        DeleteAccountService service = new DeleteAccountService(id -> calls.add("forget"), id -> calls.add("delete"));

        assertThrows(IllegalArgumentException.class, () -> service.delete(null));
        assertEquals(List.of(), calls);
    }
}
