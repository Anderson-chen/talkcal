package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;

import java.util.Objects;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 登入時，Spring Security 拿使用者打的帳號名稱來這裡查帳號，再自己用 PasswordEncoder 比對密碼。
 * 在架構上它是 inbound adapter：呼叫端是 Spring Security 的登入 filter，它只認得 use case。
 *
 * 回給 Spring Security 的「username」放的是帳號 ID，不是帳號名稱：
 * 登入之後 Authentication.getName() 拿到的就是 ID —— 授權（#104）比對擁有者、
 * 之後 Google／GitHub 登入（#105）對應到同一個人，用的都是這個值。帳號名稱只是登入時打的字。
 */
public final class AccountUserDetailsService implements UserDetailsService {

    private final FindAccountUseCase findAccount;

    public AccountUserDetailsService(FindAccountUseCase findAccount) {
        this.findAccount = Objects.requireNonNull(findAccount, "findAccount 不可為 null");
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // 查不到時的訊息不會給使用者看到：Spring Security 會把它跟密碼錯一樣處理（BadCredentials），不透露是哪一欄錯
        Account account = findAccount.byUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("沒有這個帳號"));
        return User.withUsername(account.id().toString())
                .password(account.passwordHash())
                .authorities("USER")
                .build();
    }
}
