package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;

import java.util.Objects;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 帳密登入時，Spring Security 拿使用者輸入的帳號名稱來這裡查帳號，密碼由 Spring Security 用 PasswordEncoder 比對。
 *
 * 回給 Spring Security 的 username 放帳號 ID，不放帳號名稱：登入之後 Authentication.getName() 拿到的就是 ID，
 * 比對資料擁有者、Google／GitHub 登入對到同一種身分（見 AccountPrincipals），用的都是這個值。
 */
public final class AccountUserDetailsService implements UserDetailsService {

    private final FindAccountUseCase findAccount;

    public AccountUserDetailsService(FindAccountUseCase findAccount) {
        this.findAccount = Objects.requireNonNull(findAccount, "findAccount 不可為 null");
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // 訊息不會給使用者看到：Spring Security 把這個例外跟密碼錯一樣當成 BadCredentials，不透露是哪一欄錯
        Account account = findAccount.byUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("沒有這個帳號"));
        // 用帳號名稱查到的一定有密碼：V10 的 CHECK 規定帳號名稱和密碼雜湊一起有、一起沒有
        Account.PasswordLogin login = account.password()
                .orElseThrow(() -> new UsernameNotFoundException("這個帳號不能用密碼登入"));
        return User.withUsername(account.id().toString())
                .password(login.passwordHash())
                .authorities(AccountPrincipals.AUTHORITY)
                .build();
    }
}
