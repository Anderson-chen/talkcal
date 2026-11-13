package io.github.andersonchen.talkcal.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 註冊和登入共用這一個 PasswordEncoder，原因見 SpringPasswordHasher。
 */
@Configuration(proxyBeanMethods = false)
class PasswordHashingConfiguration {

    /**
     * 預設是 BCrypt，存起來的雜湊值前面帶著「{bcrypt}」：之後要換演算法（例如 Argon2），
     * 舊的雜湊照樣認得出來、照樣能登入，新註冊的才用新演算法，不必強迫所有人重設密碼。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
