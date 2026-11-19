package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 訪客的登入狀態：session 裡記著「這個 session 是哪個訪客」，瀏覽器另外存一個訪客 cookie。
 *
 * 訪客 cookie 跟 session cookie 分開：session 關掉瀏覽器就沒了、閒置 8 小時過期、app 重新部署也會清掉，
 * 訪客 cookie 留 7 天（talkcal.guest.lifetime），讓同一個瀏覽器回來還是同一個訪客。
 * HttpOnly：JavaScript 讀不到，遇到 XSS 也拿不走；只送到 /api，前端的靜態檔請求不帶。
 */
public final class GuestSessions {

    public static final String COOKIE = "talkcal_guest";

    // 這個 session 原本是哪個訪客：登入別的帳號時，從這個屬性取出要搬資料的訪客。
    // Spring Security 登入時換 session ID 會保留 session 的屬性，所以登入成功時還讀得到
    private static final String GUEST_ATTRIBUTE = GuestSessions.class.getName() + ".guest";

    private final MergeGuestUseCase mergeGuest;
    private final boolean secureCookie;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    // 跟 csrf.spa() 用同一個 cookie 名稱（XSRF-TOKEN），只拿來清掉舊的 token
    private final CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();

    /** secureCookie：只在 HTTPS 送（雲端），跟 session cookie 的 server.servlet.session.cookie.secure 一樣 */
    public GuestSessions(MergeGuestUseCase mergeGuest, boolean secureCookie) {
        this.mergeGuest = Objects.requireNonNull(mergeGuest, "mergeGuest 不可為 null");
        this.secureCookie = secureCookie;
    }

    /**
     * 以這個訪客登入：換新的 session ID（防 session fixation）、清掉舊的 CSRF token（前端下一次會拿到新的），
     * 再寫回訪客 cookie，期限往後延。
     */
    public void signIn(HttpServletRequest request, HttpServletResponse response, GuestPass pass) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        Authentication authentication = AccountPrincipals.guest(pass.accountId());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(GUEST_ATTRIBUTE, pass.accountId().toString());
        csrfTokens.saveToken(null, request, response);
        writeCookie(response, pass.token(), Duration.between(Instant.now(), pass.expiresAt()));
    }

    /**
     * 登入成功（帳密、Google、GitHub）之後呼叫：這個 session 原本是訪客的話，把訪客的資料併進剛登入的帳號，
     * 訪客 cookie 清掉。訪客註冊成會員再登入時是同一個帳號，MergeGuestUseCase 什麼都不做，只清 cookie。
     */
    public void afterSignIn(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Optional<AccountId> guest = guestOf(request);
        if (guest.isEmpty()) {
            return;
        }
        leaveGuest(request, response);
        mergeGuest.mergeInto(guest.get(), AccountId.of(authentication.getName()));
    }

    /** 訪客升級成會員之後：不再是訪客，cookie 和 session 裡的標記都清掉 */
    public void upgraded(HttpServletRequest request, HttpServletResponse response) {
        leaveGuest(request, response);
    }

    private void leaveGuest(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(GUEST_ATTRIBUTE);
        }
        clearCookie(response);
    }

    public void clearCookie(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    private static Optional<AccountId> guestOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(GUEST_ATTRIBUTE) instanceof String id)) {
            return Optional.empty();
        }
        return Optional.of(AccountId.of(id));
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
