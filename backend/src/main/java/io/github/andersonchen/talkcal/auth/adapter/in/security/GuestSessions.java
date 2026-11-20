package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.util.WebUtils;

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

    private static final Logger log = LoggerFactory.getLogger(GuestSessions.class);

    public static final String COOKIE = "talkcal_guest";

    // 這個 session 原本是哪個訪客：登入別的帳號時，從這個屬性取出要搬資料的訪客。
    // Spring Security 登入時換 session ID 會保留 session 的屬性，所以登入成功時還讀得到
    private static final String GUEST_ATTRIBUTE = GuestSessions.class.getName() + ".guest";
    // 這個 session 上一次記下訪客在使用的時間（epoch 毫秒）
    private static final String ACTIVE_AT_ATTRIBUTE = GuestSessions.class.getName() + ".activeAt";

    private final MergeGuestUseCase mergeGuest;
    private final boolean secureCookie;
    private final Duration keepActiveEvery;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    // 跟 csrf.spa() 用同一個 cookie 名稱（XSRF-TOKEN），只拿來清掉舊的 token
    private final CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();

    /**
     * secureCookie：只在 HTTPS 送（雲端），跟 session cookie 的 server.servlet.session.cookie.secure 一樣；
     * keepActiveEvery：session 還在的訪客，隔多久記一次還在使用（見 dueToKeepActive）
     */
    public GuestSessions(MergeGuestUseCase mergeGuest, boolean secureCookie, Duration keepActiveEvery) {
        this.mergeGuest = Objects.requireNonNull(mergeGuest, "mergeGuest 不可為 null");
        this.secureCookie = secureCookie;
        this.keepActiveEvery = Objects.requireNonNull(keepActiveEvery, "keepActiveEvery 不可為 null");
    }

    /** 請求帶的訪客 cookie 裡的 token */
    static Optional<String> tokenOf(HttpServletRequest request) {
        return Optional.ofNullable(WebUtils.getCookie(request, COOKIE))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank());
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
        HttpSession session = request.getSession();
        session.setAttribute(GUEST_ATTRIBUTE, pass.accountId().toString());
        session.setAttribute(ACTIVE_AT_ATTRIBUTE, Instant.now().toEpochMilli());
        csrfTokens.saveToken(null, request, response);
        writeCookie(response, pass.token(), Duration.between(Instant.now(), pass.expiresAt()));
    }

    /**
     * 這個 session 是訪客，而且離上一次記下使用時間已經過了 keepActiveEvery，就回那個訪客，否則 empty。
     * 拿到訪客的呼叫端記下這次使用（GuestAccessUseCase.keepActive），再把結果交給 keptActive。
     *
     * session 還在的請求不經過 token，不記的話訪客帳號的最後使用時間停在登入那一刻，連續用超過期限（talkcal.guest.lifetime）
     * 會被清理刪掉，訪客 cookie 也會在用到一半時過期。不是每個請求都記：每次都要寫資料庫，跟期限比，晚 keepActiveEvery 記沒有差別
     */
    public Optional<AccountId> dueToKeepActive(HttpServletRequest request) {
        Optional<AccountId> guest = guestOf(request);
        if (guest.isEmpty()) {
            return Optional.empty();
        }
        Object activeAt = request.getSession().getAttribute(ACTIVE_AT_ATTRIBUTE);
        boolean due = !(activeAt instanceof Long millis)
                || !Instant.ofEpochMilli(millis).plus(keepActiveEvery).isAfter(Instant.now());
        return due ? guest : Optional.empty();
    }

    /**
     * expiresAt 是 GuestAccessUseCase.keepActive 回的新期限：訪客 cookie 照新期限重寫；
     * empty 表示帳號已經不是訪客，cookie 和 session 裡的標記都清掉。
     */
    public void keptActive(HttpServletRequest request, HttpServletResponse response, Optional<Instant> expiresAt) {
        if (expiresAt.isEmpty()) {
            leaveGuest(request, response);
            return;
        }
        request.getSession().setAttribute(ACTIVE_AT_ATTRIBUTE, Instant.now().toEpochMilli());
        tokenOf(request).ifPresent(token -> writeCookie(response, token, Duration.between(Instant.now(), expiresAt.get())));
    }

    /**
     * 登入成功（帳密、Google、GitHub）之後呼叫：這個 session 原本是訪客的話，把訪客的資料併進剛登入的帳號，
     * 訪客 cookie 清掉。訪客帳密註冊後再登入、第一次用 Google、GitHub 登入時，登入的就是升級過的同一個帳號，
     * MergeGuestUseCase 什麼都不做，只清 cookie。
     */
    public void afterSignIn(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Optional<AccountId> guest = guestOf(request);
        if (guest.isEmpty()) {
            return;
        }
        try {
            mergeGuest.mergeInto(guest.get(), AccountId.of(authentication.getName()));
        } catch (IllegalStateException e) {
            // 登入本身已經成功，不因為搬資料失敗讓使用者看到登入失敗。訪客的標記和 cookie 留著：
            // 下一次在這個瀏覽器登入時再搬一次（這次搬到一半的話，搬好的那些已經在新帳號名下，剩下的下次搬）
            log.error("訪客的資料搬不過去，這次先不搬：guest={} target={}", guest.get(), authentication.getName(), e);
            return;
        }
        leaveGuest(request, response);
    }

    /** 訪客升級成會員之後：不再是訪客，cookie 和 session 裡的標記都清掉 */
    public void upgraded(HttpServletRequest request, HttpServletResponse response) {
        leaveGuest(request, response);
    }

    private void leaveGuest(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(GUEST_ATTRIBUTE);
            session.removeAttribute(ACTIVE_AT_ATTRIBUTE);
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
