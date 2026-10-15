package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres.JdbcAccountAdapter;
import io.github.andersonchen.talkcal.auth.application.domain.service.FindAccountService;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * auth 模組裡不管用哪種方式登入都需要的接線：帳號存在哪、怎麼查。
 *
 * 「怎麼登入」是另一回事，看 talkcal.auth.provider 組裝其中一套：
 * - local：本機帳密（LocalAuthConfiguration）
 * - cognito：AWS 上的 Amazon Cognito（#106）
 * 一次只會有一套 SecurityFilterChain，兩套的規則（哪些路徑要登入、沒登入回什麼）要保持一致。
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    // 一個實作同時是 Save / LoadAccountPort：碰的是同一張表
    @Bean
    JdbcAccountAdapter accountStore(JdbcClient jdbcClient) {
        return new JdbcAccountAdapter(jdbcClient);
    }

    @Bean
    FindAccountUseCase findAccount(LoadAccountPort loadAccountPort) {
        return new FindAccountService(loadAccountPort);
    }
}
