package io.github.andersonchen.talkcal.quota.application.domain.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 規則表的全部內容。規則表寫錯時建構就失敗，不等到有人呼叫那個 API 才發現。
 * 寫錯指的是規則指到不存在的計數，或照帳號計數的規則套在沒登入的等級（none）上，沒登入就沒有帳號可以分。
 */
public record QuotaRules(List<QuotaRule> rules, Map<String, QuotaBucket> buckets) {

    public static final QuotaRules NONE = new QuotaRules(List.of(), Map.of());

    /** 沒登入的等級，跟規則表 quota_rule.tier 的值一樣 */
    public static final String NOT_SIGNED_IN = "none";

    public QuotaRules {
        rules = List.copyOf(rules);
        buckets = Map.copyOf(buckets);
        for (QuotaRule rule : rules) {
            QuotaBucket bucket = buckets.get(rule.bucket());
            if (bucket == null) {
                throw new IllegalArgumentException("規則指到不存在的計數：" + rule);
            }
            if (bucket.subject() == QuotaSubject.ACCOUNT && NOT_SIGNED_IN.equals(rule.tier())) {
                throw new IllegalArgumentException("沒登入的請求沒有帳號，不能照帳號計數：" + rule);
            }
        }
    }

    public static QuotaRules of(List<QuotaRule> rules, List<QuotaBucket> buckets) {
        return new QuotaRules(rules, buckets.stream().collect(Collectors.toMap(QuotaBucket::name, Function.identity())));
    }

    /**
     * 這個請求要算進哪幾份計數，例如開訪客帳號同時算全站的名額和那個 IP 的上限；沒有規則對到就是空的，不限制
     */
    public List<QuotaBucket> bucketsFor(String method, String pathPattern, String tier) {
        return rules.stream()
                .filter(rule -> rule.matches(method, pathPattern, tier))
                .map(rule -> buckets.get(rule.bucket()))
                .toList();
    }
}
