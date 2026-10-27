package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.DeleteAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.DeleteAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.ForgetAccountDataPort;

import java.util.Objects;

/**
 * 實作「刪除帳號」：先刪資料，最後才刪帳號。
 *
 * 順序是故意的：資料和帳號在不同模組、不在同一個交易裡，中途可能只做完一半。
 * - 先刪帳號、資料刪到一半失敗 → 帳號沒了，使用者登不進來、也沒辦法再要求一次，資料就永遠留著沒人刪
 * - 先刪資料、刪帳號失敗 → 帳號還在，使用者再按一次刪除，資料那步刪 0 筆、帳號這次刪掉
 * 所以失敗時留下的永遠是「帳號還在」，可以重試。
 */
public final class DeleteAccountService implements DeleteAccountUseCase {

    private final ForgetAccountDataPort forgetAccountDataPort;
    private final DeleteAccountPort deleteAccountPort;

    public DeleteAccountService(ForgetAccountDataPort forgetAccountDataPort, DeleteAccountPort deleteAccountPort) {
        this.forgetAccountDataPort = Objects.requireNonNull(forgetAccountDataPort, "forgetAccountDataPort 不可為 null");
        this.deleteAccountPort = Objects.requireNonNull(deleteAccountPort, "deleteAccountPort 不可為 null");
    }

    @Override
    public void delete(AccountId id) {
        if (id == null) {
            throw new IllegalArgumentException("帳號 ID 不可為 null");
        }
        forgetAccountDataPort.forget(id);
        // 帳號已經不在（另一個分頁先刪了）不算錯：要的結果「這個人的東西都不在了」已經達成
        deleteAccountPort.delete(id);
    }
}
