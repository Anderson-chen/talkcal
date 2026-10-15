package io.github.andersonchen.talkcal.auth.application.port.out;

/**
 * 把密碼算成雜湊值，只有它存進資料庫。
 *
 * 獨立成一個 port：雜湊演算法（BCrypt）是 Spring Security 提供的，application 層不准依賴 Spring（ArchitectureTest）。
 * 比對密碼不在這裡：登入時由 Spring Security 用同一個 PasswordEncoder 比對，這邊只管註冊時算好存起來。
 */
public interface HashPasswordPort {

    String hash(String rawPassword);
}
