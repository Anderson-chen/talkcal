package io.github.andersonchen.talkcal.auth.adapter.out.security;

import io.github.andersonchen.talkcal.auth.application.port.out.HashPasswordPort;

import java.util.Objects;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用 Spring Security 的 PasswordEncoder 算密碼雜湊。
 * 登入時比對密碼的是同一個 PasswordEncoder（app.config 裡只建一個），註冊和登入才會用同一套演算法。
 */
public final class SpringPasswordHasher implements HashPasswordPort {

    private final PasswordEncoder encoder;

    public SpringPasswordHasher(PasswordEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder 不可為 null");
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
