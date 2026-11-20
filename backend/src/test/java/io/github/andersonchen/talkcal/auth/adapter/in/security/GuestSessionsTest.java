package io.github.andersonchen.talkcal.auth.adapter.in.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.MergeGuestUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.MergePendingException;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
@DisplayName("GuestSessions、GuestCookieFilter：以訪客登入的 session")
class GuestSessionsTest {

    private static final AccountId GUEST = AccountId.newId();
    private static final AccountId AMY = AccountId.newId();
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String TOKEN = "guest-token";

    /** guest 是 false 時帳號已經不是訪客；broken 時兩個方法都丟 IllegalStateException，當成資料庫出錯 */
    private static final class FakeGuestAccess implements GuestAccessUseCase {
        boolean guest = true;
        boolean broken;
        int keptActive;

        @Override
        public GuestPass start() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GuestPass> resume(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> keepActive(AccountId account) {
            failIfBroken();
            keptActive++;
            return guest ? Optional.of(Instant.now().plus(Duration.ofDays(7))) : Optional.empty();
        }

        @Override
        public boolean isGuest(AccountId account) {
            failIfBroken();
            return guest;
        }

        private void failIfBroken() {
            if (broken) {
                throw new IllegalStateException("讀不到帳號");
            }
        }
    }

    private final FakeGuestAccess access = new FakeGuestAccess();

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

    /** 跑一次 GuestCookieFilter，回傳後面的 filter 看到的登入者（沒登入是 null） */
    private Authentication filter(GuestSessions sessions, MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        new GuestCookieFilter(access, sessions).doFilter(request, response,
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    private static boolean guestCookieCleared(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith(GuestSessions.COOKIE + "=;") && header.contains("Max-Age=0"));
    }

    private static Authentication amy() {
        return UsernamePasswordAuthenticationToken.authenticated(AMY.toString(), null, List.of());
    }

    // ── 帳號已經不是訪客 ───────────────────────────────────────────────

    @Test
    @DisplayName("帳號已經不是訪客（註冊了、併進別的帳號被刪掉）：以那個訪客登入的 session 登出，後面的 filter 看到的是沒登入，訪客 cookie 清掉")
    void formerGuestIsSignedOut() throws Exception {
        GuestSessions sessions = sessions((guest, target) -> { }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        MockHttpServletResponse response = new MockHttpServletResponse();
        access.guest = false;

        assertNull(filter(sessions, request, response));
        assertNull(request.getSession(false), "session 作廢了");
        assertTrue(guestCookieCleared(response), response.getHeaders("Set-Cookie").toString());
        assertEquals(0, access.keptActive, "還不到記下使用時間的時候，只讀等級");
    }

    @Test
    @DisplayName("記下使用時間的那次也看結果：keepActive 回 empty（已經不是訪客）一樣登出")
    void formerGuestIsSignedOutWhenKeepingActive() throws Exception {
        GuestSessions sessions = sessions((guest, target) -> { }, Duration.ZERO);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        access.guest = false;

        assertNull(filter(sessions, request, new MockHttpServletResponse()));
        assertEquals(1, access.keptActive);
    }

    @Test
    @DisplayName("反例：還是訪客，照常是登入狀態，session 還在")
    void guestStaysSignedIn() throws Exception {
        GuestSessions sessions = sessions((guest, target) -> { }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);

        assertEquals(GUEST.toString(), filter(sessions, request, new MockHttpServletResponse()).getName());
        assertEquals(Optional.of(GUEST), GuestSessions.guestOf(request));
    }

    @Test
    @DisplayName("反例：查不到還是不是訪客（資料庫出錯），這個請求照常是登入狀態")
    void unreadableAccountKeepsTheRequestGoing() throws Exception {
        GuestSessions sessions = sessions((guest, target) -> { }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        access.broken = true;

        assertEquals(GUEST.toString(), filter(sessions, request, new MockHttpServletResponse()).getName());
        assertEquals(Optional.of(GUEST), GuestSessions.guestOf(request));
    }

    @Test
    @DisplayName("反例：已經登入別的帳號的 session（合併沒記下），訪客不是訪客了也不登出，只清掉訪客的標記和 cookie")
    void otherAccountStaysSignedIn() throws Exception {
        GuestSessions sessions = sessions((guest, target) -> { }, HOUR);
        MockHttpServletRequest request = nextRequestOfGuest(sessions);
        SecurityContextHolder.getContext().setAuthentication(amy());
        MockHttpServletResponse response = new MockHttpServletResponse();
        access.guest = false;

        assertEquals(AMY.toString(), filter(sessions, request, response).getName());
        assertEquals(Optional.empty(), GuestSessions.guestOf(request));
        assertTrue(guestCookieCleared(response));
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
