package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountTierFilter;
import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.AssistantGaveUpException;
import io.github.andersonchen.talkcal.quota.adapter.in.web.QuotaInterceptor;
import io.github.andersonchen.talkcal.quota.adapter.out.counter.redis.RedisQuotaCounterAdapter;
import io.github.andersonchen.talkcal.quota.adapter.out.persistence.postgres.JdbcQuotaRulesAdapter;
import io.github.andersonchen.talkcal.quota.application.domain.service.ConsumeQuotaService;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase;
import io.github.andersonchen.talkcal.quota.application.port.out.LoadQuotaRulesPort;
import io.github.andersonchen.talkcal.quota.application.port.out.QuotaCounterPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/** 每日次數限制。規則和計數放哪、為什麼，見 V15__guest_mode_and_daily_quota.sql */
@Configuration(proxyBeanMethods = false)
class QuotaConfiguration {

    @Bean
    LoadQuotaRulesPort quotaRules(JdbcClient jdbcClient) {
        return new JdbcQuotaRulesAdapter(jdbcClient);
    }

    @Bean
    QuotaCounterPort quotaCounter(StringRedisTemplate redis) {
        return new RedisQuotaCounterAdapter(redis);
    }

    // 換日照 calendar.zone：跟使用者看到的「今天」同一天
    @Bean
    ConsumeQuotaUseCase consumeQuota(LoadQuotaRulesPort quotaRules, QuotaCounterPort quotaCounter,
                                     @Value("${calendar.zone}") ZoneId zone,
                                     @Value("${talkcal.quota.rules-refresh}") Duration refresh) {
        return new ConsumeQuotaService(quotaRules, quotaCounter, Clock.system(zone), refresh);
    }

    // quota 不 import auth、calendar，在這裡設定：請求者的等級從 auth 的 filter 記在 request 上的屬性讀；
    // 失敗也照扣的是模型有回話、助理最後沒有產生回覆的那次（理由見 AssistantGaveUpException）
    @Bean
    WebMvcConfigurer quotaInterceptor(ConsumeQuotaUseCase consumeQuota) {
        QuotaInterceptor interceptor = new QuotaInterceptor(consumeQuota, AccountTierFilter::tierOf,
                AssistantGaveUpException.class::isInstance);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/api/**");
            }
        };
    }
}
