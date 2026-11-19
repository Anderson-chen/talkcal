package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 把請求者的會員等級（guest、free、paid，沒登入是 none）記在 request 的 TIER 屬性上，給每日次數和指標的 tier 標籤用。
 *
 * 在 Spring Security 的 filter 裡記：請求處理完、指標記錄的時候，Spring Security 已經清掉登入資訊，到那時才查會查不到。
 *
 * 等級查一次資料庫之後在 session 裡記 CACHE_FOR（連同帳號 ID，換人登入時對不上就重查），不是每個請求都查一次。
 * 代價：維護者用 SQL 把帳號改成付費之後，最多 CACHE_FOR 才生效（backend/README.md 的「會員等級」）。
 */
public final class AccountTierFilter extends OncePerRequestFilter {

    public static final String TIER = AccountTierFilter.class.getName() + ".tier";
    public static final String NONE = "none";

    private static final String CACHED = AccountTierFilter.class.getName() + ".cached";
    private static final Duration CACHE_FOR = Duration.ofMinutes(1);

    /** session 裡記的：哪個帳號、什麼等級、什麼時候查的 */
    private record Cached(String accountId, String tier, Instant at) implements Serializable {
    }

    private final FindAccountUseCase findAccount;

    public AccountTierFilter(FindAccountUseCase findAccount) {
        this.findAccount = Objects.requireNonNull(findAccount, "findAccount 不可為 null");
    }

    /** request 上記的等級；filter 沒執行到（例如請求在更前面就被擋下）時是 none */
    public static String tierOf(HttpServletRequest request) {
        return request.getAttribute(TIER) instanceof String tier ? tier : NONE;
    }

    /** 登入、升級之後呼叫：session 裡記的等級可能已經不對，下一個請求重查 */
    static void forget(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(CACHED);
        }
        request.removeAttribute(TIER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(TIER, currentTier(request));
        chain.doFilter(request, response);
    }

    private String currentTier(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return NONE;
        }
        String accountId = authentication.getName();
        HttpSession session = request.getSession(false);
        Instant now = Instant.now();
        if (session != null && session.getAttribute(CACHED) instanceof Cached cached
                && cached.accountId().equals(accountId) && now.isBefore(cached.at().plus(CACHE_FOR))) {
            return cached.tier();
        }
        AccountId id;
        try {
            id = AccountId.of(accountId);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
        String tier = findAccount.byId(id).map(Account::tier).map(t -> t.id()).orElse(NONE);
        if (session != null) {
            session.setAttribute(CACHED, new Cached(accountId, tier, now));
        }
        return tier;
    }
}
