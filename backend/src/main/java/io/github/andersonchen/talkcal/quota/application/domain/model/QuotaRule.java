package io.github.andersonchen.talkcal.quota.application.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * 哪個等級呼叫哪個 API 要算進哪一份計數。
 *
 * pathPattern 寫 controller 上的路徑樣板，跟 @RequestMapping 一字不差（例如 /api/calendar/events/{id}，
 * 變數名稱也要一樣）：比對的是 Spring 選好 handler 之後的那個樣板，不是請求的實際路徑，所以不用自己切路徑比對。
 */
public record QuotaRule(String method, String pathPattern, String tier, String bucket) {

    public QuotaRule {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("HTTP 方法不可為空白");
        }
        if (pathPattern == null || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("路徑要從 / 開始：" + pathPattern);
        }
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("等級不可為空白");
        }
        Objects.requireNonNull(bucket, "計數名稱不可為 null");
        method = method.toUpperCase(Locale.ROOT);
    }

    public boolean matches(String requestMethod, String requestPattern, String requestTier) {
        return method.equalsIgnoreCase(requestMethod) && pathPattern.equals(requestPattern) && tier.equals(requestTier);
    }
}
