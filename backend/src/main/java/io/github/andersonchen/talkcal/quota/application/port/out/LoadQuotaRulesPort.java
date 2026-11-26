package io.github.andersonchen.talkcal.quota.application.port.out;

import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRules;

/** 讀整份規則表。讀不到丟 IllegalStateException */
public interface LoadQuotaRulesPort {

    QuotaRules load();
}
