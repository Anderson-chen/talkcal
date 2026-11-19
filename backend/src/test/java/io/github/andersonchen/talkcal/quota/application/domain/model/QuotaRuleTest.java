package io.github.andersonchen.talkcal.quota.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QuotaRule、QuotaRules：哪個請求算進哪一份計數")
class QuotaRuleTest {

    private static final QuotaRule UPDATE = new QuotaRule("put", "/api/calendar/events/{id}", "guest", "writes");

    @Test
    @DisplayName("{id} 對到任意一段；方法不分大小寫")
    void placeholderMatchesOneSegment() {
        assertTrue(UPDATE.matches("PUT", "/api/calendar/events/42", "guest"));
    }

    @Test
    @DisplayName("反例：等級不同、方法不同、段數不同、{id} 是空的都不算")
    void mismatches() {
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events/42", "free"));
        assertFalse(UPDATE.matches("DELETE", "/api/calendar/events/42", "guest"));
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events", "guest"));
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events/42/extra", "guest"));
        assertFalse(UPDATE.matches("PUT", "/api/calendar/events/", "guest"));
    }

    @Test
    @DisplayName("規則表找得到就回那份計數；反例：沒有規則對到是 empty（不限制）")
    void bucketLookup() {
        QuotaBucket writes = new QuotaBucket("writes", QuotaSubject.IP, 10, "用完了");
        QuotaRules rules = QuotaRules.of(List.of(UPDATE), List.of(writes));
        assertEquals(Optional.of(writes), rules.bucketFor("PUT", "/api/calendar/events/42", "guest"));
        assertEquals(Optional.empty(), rules.bucketFor("GET", "/api/calendar/events", "guest"));
    }

    @Test
    @DisplayName("反例：規則指到不存在的計數，讀規則表時就失敗")
    void danglingBucketIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> QuotaRules.of(List.of(UPDATE), List.of()));
    }
}
