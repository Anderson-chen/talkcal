package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 把請求者的會員等級記在 request 上，給每日次數和指標的 tier 標籤用：guest、free、paid；
 * 沒登入、或 session 登入的帳號已經刪掉是 none；讀不到是 unknown。
 *
 * 在 Spring Security 的 filter 裡記：請求處理完、指標記錄的時候，Spring Security 已經清掉登入資訊，到那時才查會查不到。
 *
 * 每個登入的請求都用主鍵查一次 account.tier，不快取：維護者用 SQL 改成付費、訪客註冊升級之後，下一個請求就是新的等級。
 *
 * 讀不到（資料庫出錯）時記成 unknown，請求照常往下走。例外從 filter 丟出去的話，回應會變成 Spring Boot 預設的 500 JSON，
 * 錯誤訊息是英文的 Internal Server Error，不是各 controller 回的 502 中文 JSON。
 * unknown 對不到任何每日次數的規則，那段時間不限制次數，跟 Redis 無法連線時一樣。
 */
public final class AccountTierFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccountTierFilter.class);

    private static final String NONE = "none";
    private static final String UNKNOWN = "unknown";

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
        try {
            return AccountPrincipals.signedInAccount()
                    .flatMap(findAccount::byId)
                    .map(Account::tier)
                    .map(MembershipTier::id)
                    .orElse(NONE);
        } catch (IllegalStateException e) {
            log.warn("讀不到帳號的等級，這個請求記成 {}", UNKNOWN, e);
            return UNKNOWN;
        }
    }
}
