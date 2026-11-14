package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.Optional;

/**
 * byUsername 給帳密登入時查帳號（密碼由 Spring Security 比對），byId 給登入之後從 session 裡的帳號 ID 查回帳號。
 *
 * 帳號名稱不合規則（例如輸入了中文）不是錯，就是查不到：登入失敗時不該透露是帳號名稱錯還是密碼錯。
 */
public interface FindAccountUseCase {

    Optional<Account> byUsername(String username);

    Optional<Account> byId(AccountId id);
}
