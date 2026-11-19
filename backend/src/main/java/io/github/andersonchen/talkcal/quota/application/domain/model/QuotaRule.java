package io.github.andersonchen.talkcal.quota.application.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * 哪個等級呼叫哪個 API 要算進哪一份計數。
 *
 * pathPattern 一段一段比：段數要一樣，{id} 或 * 對到任意一段（不可為空），其他要一字不差。
 * 只支援這麼多：規則寫的都是 API 的路徑樣板，用不到 ** 或正規表示式，規則表也比較好讀。
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

    public boolean matches(String requestMethod, String path, String requestTier) {
        if (!method.equalsIgnoreCase(requestMethod) || !tier.equals(requestTier) || path == null) {
            return false;
        }
        String[] want = pathPattern.split("/", -1);
        String[] got = path.split("/", -1);
        if (want.length != got.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            boolean any = want[i].equals("*") || (want[i].startsWith("{") && want[i].endsWith("}"));
            if (any ? got[i].isEmpty() : !want[i].equals(got[i])) {
                return false;
            }
        }
        return true;
    }
}
