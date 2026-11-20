package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 把請求者的會員等級（guest、free、paid，沒登入是 none）記在 request 上，給每日次數和指標的 tier 標籤用。
 *
 * 在 Spring Security 的 filter 裡記：請求處理完、指標記錄的時候，Spring Security 已經清掉登入資訊，到那時才查會查不到。
 *
 * 每個登入的請求都用主鍵查一次 account.tier，不快取：維護者用 SQL 改成付費、訪客註冊升級之後，下一個請求就是新的等級。
 */
public final class AccountTierFilter extends OncePerRequestFilter {

    private static final String NONE = "none";

    private static final String TIER = AccountTierFilter.class.getName() + ".tier";

    private final FindAccountUseCase findAccount;

    public AccountTierFilter(FindAccountUseCase findAccount) {
        this.findAccount = Objects.requireNonNull(findAccount, "findAccount 不可為 null");
    }

    /** request 上記的等級；filter 沒執行到（例如請求在更前面就被擋下）時是 none */
    public static String tierOf(HttpServletRequest request) {
        return request.getAttribute(TIER) instanceof String tier ? tier : NONE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(TIER, currentTier());
        chain.doFilter(request, response);
    }

    private String currentTier() {
        return AccountPrincipals.signedInAccount()
                .flatMap(findAccount::byId)
                .map(Account::tier)
                .map(MembershipTier::id)
                .orElse(NONE);
    }
}
