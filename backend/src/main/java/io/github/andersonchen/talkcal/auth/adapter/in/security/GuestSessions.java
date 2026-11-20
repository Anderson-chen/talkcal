package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.MergePendingException;

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
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
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
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
    // 跟 csrf.spa() 用同一個 cookie 名稱（XSRF-TOKEN），只拿來清掉舊的 token
    private final CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();

    /**
     * secureCookie：只在 HTTPS 送（雲端），跟 session cookie 的 server.servlet.session.cookie.secure 一樣。
     * keepActiveEvery：session 還在的訪客，隔多久記一次使用時間（見 dueToKeepActive）
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
     * 以這個訪客登入：換新的 session ID 防 session fixation，清掉舊的 CSRF token 讓前端下一次拿到新的，
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
     * 這個 session 是訪客，而且離上一次記下使用時間已經過了 keepActiveEvery。
     * 是的話，呼叫端記下這次使用（GuestAccessUseCase.keepActive），再把新的期限交給 keptActive。
     *
     * session 還在的請求不經過 token，不記的話訪客帳號的最後使用時間停在登入那一刻，連續用超過期限（talkcal.guest.lifetime）
     * 會被清理刪掉，訪客 cookie 也會在用到一半時過期。不是每個請求都記：每次都要寫資料庫，跟期限比，晚 keepActiveEvery 記沒有差別
     */
    public boolean dueToKeepActive(HttpServletRequest request) {
        if (guestOf(request).isEmpty()) {
            return false;
        }
        Object activeAt = request.getSession().getAttribute(ACTIVE_AT_ATTRIBUTE);
        return !(activeAt instanceof Long millis)
                || !Instant.ofEpochMilli(millis).plus(keepActiveEvery).isAfter(Instant.now());
    }

    /** expiresAt 是 GuestAccessUseCase.keepActive 回的新期限：訪客 cookie 照新期限重寫 */
    public void keptActive(HttpServletRequest request, HttpServletResponse response, Instant expiresAt) {
        request.getSession().setAttribute(ACTIVE_AT_ATTRIBUTE, Instant.now().toEpochMilli());
        tokenOf(request).ifPresent(token -> writeCookie(response, token, Duration.between(Instant.now(), expiresAt)));
    }

    /**
     * session 記著的帳號已經不是訪客（註冊了、併進別的帳號被刪掉）。這個 session 登入的就是那個訪客的話，整個 session 登出，
     * 這個請求和之後的請求都是沒登入：在共用電腦上複製了訪客 cookie 的人，才不會在真正的使用者註冊之後讀寫到會員帳號，
     * 也不會用已經刪掉的訪客帳號繞過次數限制。
     * 已經登入別的帳號的 session（登入時合併沒記下，見 afterSignIn）照樣是登入狀態，只清掉訪客的標記。
     * 兩種都清掉訪客 cookie。
     */
    public void notGuestAnymore(HttpServletRequest request, HttpServletResponse response) {
        Optional<AccountId> guest = guestOf(request);
        if (guest.isPresent() && guest.equals(AccountPrincipals.signedInAccount())) {
            logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
            clearCookie(response);
        } else {
            leaveGuest(request, response);
        }
    }

    /**
     * 登入成功（帳密、Google、GitHub）之後呼叫：這個 session 原本是訪客的話，把訪客的資料併進剛登入的帳號，
     * 訪客 cookie 清掉。訪客帳密註冊後再登入、第一次用 Google、GitHub 登入時，登入的就是升級過的同一個帳號，
     * MergeGuestUseCase 什麼都不做，只清 cookie。
     *
     * 搬資料失敗也不讓使用者看到登入失敗：登入本身已經成功。
     */
    public void afterSignIn(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Optional<AccountId> guest = guestOf(request);
        if (guest.isEmpty()) {
            return;
        }
        try {
            mergeGuest.mergeInto(guest.get(), AccountId.of(authentication.getName()));
        } catch (MergePendingException e) {
            // 合併已經記下，剩下的由排程搬完，不靠這個 session 和訪客 cookie
            log.warn("訪客的資料這次沒搬完，之後由排程再搬：guest={} target={}", guest.get(), authentication.getName(), e);
        } catch (IllegalStateException e) {
            // 合併沒記下，訪客的標記和 cookie 留著：下一次在這個瀏覽器登入時再合併一次
            log.error("訪客的合併沒記下，這次先不搬：guest={} target={}", guest.get(), authentication.getName(), e);
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

    static Optional<AccountId> guestOf(HttpServletRequest request) {
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
