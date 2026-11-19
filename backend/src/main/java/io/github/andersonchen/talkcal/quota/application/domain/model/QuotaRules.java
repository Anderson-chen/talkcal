package io.github.andersonchen.talkcal.quota.application.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 規則表的全部內容。規則指到不存在的計數名稱時，建構就失敗：那是規則表寫錯，不該等到有人呼叫那個 API 才發現 */
public record QuotaRules(List<QuotaRule> rules, Map<String, QuotaBucket> buckets) {

    public static final QuotaRules NONE = new QuotaRules(List.of(), Map.of());

    public QuotaRules {
        rules = List.copyOf(rules);
        buckets = Map.copyOf(buckets);
        for (QuotaRule rule : rules) {
            if (!buckets.containsKey(rule.bucket())) {
                throw new IllegalArgumentException("規則指到不存在的計數：" + rule);
            }
        }
    }

    public static QuotaRules of(List<QuotaRule> rules, List<QuotaBucket> buckets) {
        return new QuotaRules(rules, buckets.stream().collect(Collectors.toMap(QuotaBucket::name, Function.identity())));
    }

    /** 這個請求要算進哪一份計數；沒有規則對到就是 empty，不限制 */
    public Optional<QuotaBucket> bucketFor(String method, String path, String tier) {
        return rules.stream()
                .filter(rule -> rule.matches(method, path, tier))
                .findFirst()
                .map(rule -> buckets.get(rule.bucket()));
    }
}
