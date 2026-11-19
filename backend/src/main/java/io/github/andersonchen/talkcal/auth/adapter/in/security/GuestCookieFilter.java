package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * 沒登入、但帶著訪客 cookie 的請求：token 還有效就登入那個訪客帳號，這個請求和之後的請求都是登入狀態。
 * token 過期、查不到、帳號已經不是訪客，就把 cookie 清掉，請求照樣當成沒登入。
 *
 * 放在 Spring Security 的 AnonymousAuthenticationFilter 前面：讀完 session 還是沒登入才輪到這裡，
 * 已經登入的請求不碰 cookie。
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
        Optional<String> token = guestToken(request);
        if (token.isPresent() && !signedIn()) {
            try {
                guestAccess.resume(token.get()).ifPresentOrElse(
                        pass -> guestSessions.signIn(request, response, pass),
                        () -> guestSessions.clearCookie(response));
            } catch (IllegalStateException e) {
                // 資料庫暫時讀不到：這次當成沒登入，cookie 留著，下一次再試
                log.warn("讀不到訪客 token，這個請求當成沒登入", e);
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean signedIn() {
        return AccountPrincipals.signedIn(SecurityContextHolder.getContext().getAuthentication());
    }

    private static Optional<String> guestToken(HttpServletRequest request) {
        return Optional.ofNullable(WebUtils.getCookie(request, GuestSessions.COOKIE))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank());
    }
}
