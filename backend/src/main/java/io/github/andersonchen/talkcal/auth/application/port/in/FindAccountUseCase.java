package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.Optional;

/**
 * 查帳號（inbound port）。
 * - byUsername：登入時，Spring Security 拿使用者打的帳號名稱來查，再自己比對密碼
 * - byId：登入之後，session 裡記的是帳號 ID，要顯示「目前是誰」時用它查回帳號名稱
 *
 * 帳號名稱不合規則（例如打了中文）不是錯，就是查不到：登入失敗時不該透露「是帳號名稱錯還是密碼錯」。
 */
public interface FindAccountUseCase {

    Optional<Account> byUsername(String username);

    Optional<Account> byId(AccountId id);
}
