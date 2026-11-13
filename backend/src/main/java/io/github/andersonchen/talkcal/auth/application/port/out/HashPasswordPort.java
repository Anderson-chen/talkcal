package io.github.andersonchen.talkcal.auth.application.port.out;

/**
 * 獨立成 port：雜湊演算法是 Spring Security 提供的，application 層不准依賴 Spring（ArchitectureTest）。
 * 只管註冊時算雜湊；登入時的比對由 Spring Security 用同一個 PasswordEncoder 做。
 */
public interface HashPasswordPort {

    String hash(String rawPassword);
}
