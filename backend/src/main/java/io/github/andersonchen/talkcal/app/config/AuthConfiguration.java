package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;
import io.github.andersonchen.talkcal.auth.adapter.in.security.GuestSessions;
import io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres.JdbcAccountAdapter;
import io.github.andersonchen.talkcal.auth.adapter.out.security.spring.SpringPasswordHasher;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.service.FindAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.GuestAccountsService;
import io.github.andersonchen.talkcal.auth.application.domain.service.RegisterAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.SignInExternallyService;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.OwnedDataPort;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.OwnedDataUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

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

    // ── 訪客 ──────────────────────────────────────────────────────────

    // 帳號名下的行程、對話在 calendar：auth 不 import calendar，在這裡把 auth 的 OwnedDataPort 設定成呼叫 calendar。
    // 對話的歸檔不在 calendar 的 use case 裡（見 OwnedDataUseCase），接在後面，順序跟 OwnedDataService 一樣先資料庫、再歸檔
    @Bean
    OwnedDataPort accountOwnedData(OwnedDataUseCase calendarData, AssistantMemoryArchive conversationArchive) {
        return new OwnedDataPort() {
            @Override
            public void transfer(AccountId from, AccountId to) {
                calendarData.transfer(new OwnerId(from.value()), new OwnerId(to.value()));
                conversationArchive.transfer(new OwnerId(from.value()), new OwnerId(to.value()));
            }

            @Override
            public void deleteAll(AccountId owner) {
                calendarData.deleteAll(new OwnerId(owner.value()));
                conversationArchive.deleteAll(new OwnerId(owner.value()));
            }
        };
    }

    // 回傳型別寫實作：同一個物件是好幾個 use case（開帳號、合併、清理），理由見 CalendarConfiguration.calendarEventStore
    @Bean
    GuestAccountsService guestAccounts(JdbcAccountAdapter accountStore, OwnedDataPort ownedData,
                                       @Value("${talkcal.guest.lifetime}") Duration lifetime) {
        return new GuestAccountsService(accountStore, accountStore, ownedData, lifetime, Clock.systemUTC());
    }

    // 訪客 cookie 只在 HTTPS 送：跟 session cookie 用同一個設定，雲端（compose.ec2.yaml）一起打開
    @Bean
    GuestSessions guestSessions(GuestAccountsService guestAccounts,
                                @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                                @Value("${talkcal.guest.keep-active-every}") Duration keepActiveEvery) {
        return new GuestSessions(guestAccounts, secureCookie, keepActiveEvery);
    }
}
