package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres.JdbcAccountAdapter;
import io.github.andersonchen.talkcal.auth.adapter.out.security.spring.SpringPasswordHasher;
import io.github.andersonchen.talkcal.auth.application.domain.service.FindAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.RegisterAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.SignInExternallyService;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * auth 模組自己的 use case 和 port：帳號存在哪、怎麼註冊、怎麼查、別人驗過的身分怎麼對到帳號。
 * Spring Security 的接線在 SecurityConfiguration。
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    // 一個實作同時是 Save / LoadAccountPort：碰的是同一組表。
    // 交易用在「第一次用 Google 登入」：帳號和它的 identity 要一起存進去或一起不算
    @Bean
    JdbcAccountAdapter accountStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAccountAdapter(jdbcClient, new TransactionTemplate(transactionManager));
    }

    // PasswordEncoder 來自 PasswordHashingConfiguration
    @Bean
    RegisterAccountUseCase registerAccount(SaveAccountPort saveAccountPort, PasswordEncoder passwordEncoder) {
        return new RegisterAccountService(saveAccountPort, new SpringPasswordHasher(passwordEncoder));
    }

    @Bean
    FindAccountUseCase findAccount(LoadAccountPort loadAccountPort) {
        return new FindAccountService(loadAccountPort);
    }

    // 「Google 的這個人是哪個帳號」：Google、GitHub 用它：別人驗過身分，這裡對到帳號
    @Bean
    SignInExternallyUseCase signInExternally(LoadAccountPort loadAccountPort, SaveAccountPort saveAccountPort) {
        return new SignInExternallyService(loadAccountPort, saveAccountPort);
    }
}
