package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres.JdbcAccountAdapter;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.service.DeleteAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.FindAccountService;
import io.github.andersonchen.talkcal.auth.application.domain.service.SignInExternallyService;
import io.github.andersonchen.talkcal.auth.application.port.in.DeleteAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.DeleteAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.ForgetAccountDataPort;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.ForgetOwnerUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * auth 模組裡不管用哪種方式登入都需要的接線：帳號存在哪、怎麼查、別人驗過的身分怎麼對到帳號。
 *
 * 「怎麼登入」是另一回事，看 talkcal.auth.provider 組裝：
 * - local：自己的帳密，再加上有設定的 Google、GitHub（LocalAuthConfiguration）；本機和雲端都用這套（#127）
 * 原本規劃雲端改接 Cognito（#106），決定不做了。要加別的登入方式，就照 provider 再組一套 SecurityFilterChain，
 * 規則（哪些路徑要登入、沒登入回什麼）要跟 local 那套保持一致。
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    // 一個實作同時是 Save / LoadAccountPort：碰的是同一組表。
    // 交易用在「第一次用 Google 登入」：帳號和它的 identity 要一起存進去或一起不算
    @Bean
    JdbcAccountAdapter accountStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAccountAdapter(jdbcClient, new TransactionTemplate(transactionManager));
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

    /**
     * 刪帳號之前，先請擁有資料的模組忘掉這個人（#52）。目前只有 calendar（行程、AI 助理的對話）。
     *
     * 兩個模組互不認識，翻譯在這裡做：auth 的 AccountId 和 calendar 的 OwnerId 是同一個 UUID（登入者的身分就是擁有者）。
     * 之後多一個存了帳號資料的模組（例如冪等 key、歸檔各自成模組），在這裡多接一行。
     */
    @Bean
    ForgetAccountDataPort forgetAccountData(ForgetOwnerUseCase forgetOwner) {
        return (AccountId id) -> forgetOwner.forget(new OwnerId(id.value()));
    }

    @Bean
    DeleteAccountUseCase deleteAccount(ForgetAccountDataPort forgetAccountData, DeleteAccountPort deleteAccountPort) {
        return new DeleteAccountService(forgetAccountData, deleteAccountPort);
    }
}
