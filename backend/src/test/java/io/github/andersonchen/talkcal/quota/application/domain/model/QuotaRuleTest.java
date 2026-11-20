package io.github.andersonchen.talkcal.quota.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QuotaRule、QuotaRules：哪個請求算進哪一份計數")
class QuotaRuleTest {

    private static final QuotaRule UPDATE = new QuotaRule("put", "/api/calendar/events/{id}", "guest", "writes");

    @Test
    @DisplayName("Spring 選好的路徑樣板一樣就算；規則表的方法不分大小寫（建構時轉成大寫）")
    void samePatternMatches() {
        assertTrue(UPDATE.matches("PUT", "/api/calendar/events/{id}", "guest"));
    }

    @Test
    @DisplayName("反例：等級不同、方法不同、樣板不同（實際路徑、別的端點）都不算")
    void mismatches() {
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events/{id}", "free"));
        assertFalse(UPDATE.matches("DELETE", "/api/calendar/events/{id}", "guest"));
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events/42", "guest"));
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events", "guest"));
    }

    @Test
    @DisplayName("規則表找得到就回那幾份計數；反例：沒有規則對到是空的（不限制）")
    void bucketLookup() {
        QuotaBucket writes = new QuotaBucket("writes", QuotaSubject.IP, 10, "用完了");
        QuotaRules rules = QuotaRules.of(List.of(UPDATE), List.of(writes));
        assertEquals(List.of(writes), rules.bucketsFor("PUT", "/api/calendar/events/{id}", "guest"));
        assertEquals(List.of(), rules.bucketsFor("GET", "/api/calendar/events", "guest"));
    }

    @Test
    @DisplayName("反例：規則表寫錯，讀的時候就失敗：規則指到不存在的計數、照帳號計數的套在沒登入上")
    void badTablesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> QuotaRules.of(List.of(UPDATE), List.of()));
        assertThrows(IllegalArgumentException.class, () -> QuotaRules.of(
                List.of(new QuotaRule("POST", "/api/auth/guest", "none", "per-account")),
                List.of(new QuotaBucket("per-account", QuotaSubject.ACCOUNT, 1, "用完了"))));
    }
}
