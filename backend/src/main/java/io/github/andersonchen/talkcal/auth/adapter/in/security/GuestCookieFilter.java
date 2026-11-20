package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 沒登入、但帶著訪客 cookie 的請求：token 還有效就登入那個訪客帳號，這個請求和之後的請求都是登入狀態。
 * token 過期、查不到、帳號已經不是訪客，就把 cookie 清掉，請求照樣當成沒登入。
 *
 * session 記著訪客的請求，每次都用主鍵查一次帳號還是不是訪客，不快取：註冊、併進別的帳號之後，
 * 其他瀏覽器的下一個請求就要登出（理由見 GuestSessions.notGuestAnymore）。
 * 隔一段時間（見 GuestSessions.dueToKeepActive）改成記下使用時間，那句 SQL 也判斷了還是不是訪客。
 *
 * 要排在 Spring Security 讀完 session 之後：讀完還是沒登入才用 cookie。
 * 也要排在 AuthorizationFilter 之前：登出之後，同一個請求就當成沒登入，要登入的 API 回 401。
 * 排在哪幾個 filter 前面見 SecurityConfiguration。
 */
public final class GuestCookieFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GuestCookieFilter.class);

    private final GuestAccessUseCase guestAccess;
    private final GuestSessions guestSessions;

    public GuestCookieFilter(GuestAccessUseCase guestAccess, GuestSessions guestSessions) {
        this.guestAccess = Objects.requireNonNull(guestAccess, "guestAccess 不可為 null");
        this.guestSessions = Objects.requireNonNull(guestSessions, "guestSessions 不可為 null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (signedIn()) {
            checkGuest(request, response);
        } else {
            GuestSessions.tokenOf(request).ifPresent(token -> resume(request, response, token));
        }
        chain.doFilter(request, response);
    }

    private void resume(HttpServletRequest request, HttpServletResponse response, String token) {
        try {
            guestAccess.resume(token).ifPresentOrElse(
                    pass -> guestSessions.signIn(request, response, pass),
                    () -> guestSessions.clearCookie(response));
        } catch (IllegalStateException e) {
            // 資料庫暫時讀不到：這次當成沒登入，cookie 留著，下一次再試
            log.warn("讀不到訪客 token，這個請求當成沒登入", e);
        }
    }

    private void checkGuest(HttpServletRequest request, HttpServletResponse response) {
        Optional<AccountId> guest = GuestSessions.guestOf(request);
        if (guest.isEmpty()) {
            return;
        }
        boolean stillGuest;
        try {
            if (guestSessions.dueToKeepActive(request)) {
                Optional<Instant> expiresAt = guestAccess.keepActive(guest.get());
                expiresAt.ifPresent(at -> guestSessions.keptActive(request, response, at));
                stillGuest = expiresAt.isPresent();
            } else {
                stillGuest = guestAccess.isGuest(guest.get());
            }
        } catch (IllegalStateException e) {
            // 資料庫暫時讀寫不到：這次照常處理，session 裡的時間沒更新，下一個請求再查、再記
            log.warn("查不到訪客 {} 還是不是訪客，這個請求照常處理", guest.get(), e);
            return;
        }
        if (!stillGuest) {
            guestSessions.notGuestAnymore(request, response);
        }
    }

    private static boolean signedIn() {
        return AccountPrincipals.signedIn(SecurityContextHolder.getContext().getAuthentication());
    }
}
