package io.github.andersonchen.talkcal.quota.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaBucket;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRule;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRules;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaSubject;
import io.github.andersonchen.talkcal.quota.application.port.out.LoadQuotaRulesPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;

/** 表的定義在 V15__guest_mode_and_daily_quota.sql */
public final class JdbcQuotaRulesAdapter implements LoadQuotaRulesPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcQuotaRulesAdapter.class);

    private final JdbcClient jdbc;

    public JdbcQuotaRulesAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public QuotaRules load() {
        try {
            List<QuotaBucket> buckets = jdbc.sql("SELECT name, subject, daily_limit, message FROM quota_bucket")
                    .query((rs, n) -> new QuotaBucket(rs.getString("name"), QuotaSubject.of(rs.getString("subject")),
                            rs.getInt("daily_limit"), rs.getString("message")))
                    .list();
            List<QuotaRule> rules = jdbc.sql("SELECT method, path_pattern, tier, bucket FROM quota_rule")
                    .query((rs, n) -> new QuotaRule(rs.getString("method"), rs.getString("path_pattern"),
                            rs.getString("tier"), rs.getString("bucket")))
                    .list();
            return QuotaRules.of(rules, buckets);
        } catch (DataAccessException | IllegalArgumentException e) {
            // 讀不到、或表裡的資料不合規則：服務沿用上一份規則，這裡留 log 讓人發現規則表出問題
            log.warn("讀不到每日次數的規則表，沿用上一份", e);
            throw new IllegalStateException("讀不到每日次數的規則表", e);
        }
    }
}
