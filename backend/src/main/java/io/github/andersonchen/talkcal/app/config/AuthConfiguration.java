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

@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    @Bean
    JdbcAccountAdapter accountStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAccountAdapter(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    RegisterAccountUseCase registerAccount(SaveAccountPort saveAccountPort, PasswordEncoder passwordEncoder) {
        return new RegisterAccountService(saveAccountPort, new SpringPasswordHasher(passwordEncoder));
    }

    @Bean
    FindAccountUseCase findAccount(LoadAccountPort loadAccountPort) {
        return new FindAccountService(loadAccountPort);
    }

    @Bean
    SignInExternallyUseCase signInExternally(LoadAccountPort loadAccountPort, SaveAccountPort saveAccountPort) {
        return new SignInExternallyService(loadAccountPort, saveAccountPort);
    }
}
