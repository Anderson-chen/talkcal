package io.github.andersonchen.talkcal.auth.adapter.in.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.MergePendingException;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 不啟動 Spring，直接傳入 Mock 的 request、response。SecurityContextHolderFilter 不在這裡，
 * 「從 session 讀出登入者」由 signIn 寫進 SecurityContextHolder 代替；整條 filter 串起來的情況在 GuestModeSystemTest。
 */
@DisplayName("GuestSessions：以訪客登入的 session")
class GuestSessionsTest {

    private static final AccountId GUEST = AccountId.newId();
    private static final AccountId AMY = AccountId.newId();
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String TOKEN = "guest-token";

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static GuestSessions sessions(MergeGuestUseCase merge, Duration keepActiveEvery) {
        return new GuestSessions(merge, false, keepActiveEvery);
    }

    /** 以 GUEST 登入之後，同一個瀏覽器的下一個請求 */
    private static MockHttpServletRequest nextRequestOfGuest(GuestSessions sessions) {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/auth/guest");
        sessions.signIn(first, new MockHttpServletResponse(), new GuestPass(GUEST, TOKEN, Instant.now().plus(Duration.ofDays(7))));
        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/api/calendar/events");
        next.setSession(first.getSession());
        next.setCookies(new Cookie(GuestSessions.COOKIE, TOKEN));
        return next;
    }

    private static boolean guestCookieCleared(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith(GuestSessions.COOKIE + "=;") && header.contains("Max-Age=0"));
    }

    private static Authentication amy() {
        return UsernamePasswordAuthenticationToken.authenticated(AMY.toString(), null, List.of());
    }

    // ── 登入時合併 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("登入時搬資料失敗、合併已經記下（MergePendingException）：訪客的標記和 cookie 照樣清掉，剩下的由排程搬")
    void pendingMergeLeavesTheGuest() {
        GuestSessions sessions = sessions((guest, target) -> {
            throw new MergePendingException(guest, target, new IllegalStateException("S3 無法連線"));
        }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessions.afterSignIn(request, response, amy());

        assertEquals(Optional.empty(), GuestSessions.guestOf(request));
        assertTrue(guestCookieCleared(response));
    }

    @Test
    @DisplayName("反例：合併沒記下（資料庫出錯）：訪客的標記和 cookie 留著，下一次在這個瀏覽器登入時再合併")
    void unrecordedMergeKeepsTheGuest() {
        GuestSessions sessions = sessions((guest, target) -> {
            throw new IllegalStateException("資料庫無法連線");
        }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessions.afterSignIn(request, response, amy());

        assertEquals(Optional.of(GUEST), GuestSessions.guestOf(request));
        assertFalse(guestCookieCleared(response));
    }
}
