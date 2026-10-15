package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountUserDetailsService;
import io.github.andersonchen.talkcal.auth.adapter.out.security.SpringPasswordHasher;
import io.github.andersonchen.talkcal.auth.application.domain.service.RegisterAccountService;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * 本機帳密登入（talkcal.auth.provider=local，也是沒設定時的預設）。
 *
 * 瀏覽器和後端之間只有一個 session cookie（HttpOnly，JavaScript 讀不到），前端不碰任何 token。
 * 前端和後端在同一個網域（nginx 把 /api 轉過來），所以不需要 CORS，cookie 也自然帶得上。
 *
 * 規則（Cognito 那套也要一樣）：
 * - /api 底下除了註冊、登入，一律要登入；沒登入回 401 JSON，不轉址到登入頁（前端自己決定畫登入畫面）
 * - 會改東西的請求（POST、DELETE）要帶 CSRF token：別的網站騙使用者的瀏覽器送請求時，cookie 會自動帶上，
 *   但它讀不到我們這個網域的 XSRF-TOKEN cookie，送不出對的 header
 * - actuator、API 文件不擋：它們不經過 nginx（nginx 只轉 /api），外面連不到
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "talkcal.auth.provider", havingValue = "local", matchIfMissing = true)
class LocalAuthConfiguration {

    /**
     * 預設是 BCrypt，存起來的雜湊值前面帶著「{bcrypt}」：之後要換演算法（例如 Argon2），
     * 舊的雜湊照樣認得出來、照樣能登入，新註冊的才用新演算法 —— 不必強迫所有人重設密碼。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    RegisterAccountUseCase registerAccount(SaveAccountPort saveAccountPort, PasswordEncoder passwordEncoder) {
        return new RegisterAccountService(saveAccountPort, new SpringPasswordHasher(passwordEncoder));
    }

    // 登入時 Spring Security 用它查帳號、再用上面的 PasswordEncoder 比對密碼
    @Bean
    UserDetailsService userDetailsService(FindAccountUseCase findAccount) {
        return new AccountUserDetailsService(findAccount);
    }

    @Bean
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // Spring Boot 出錯時會轉到 /error 產生錯誤回應；擋住的話，所有錯誤都會變成 401
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                // 登入：前端用表單格式送 username、password 到 /api/auth/login。
                // 成功、失敗都只回狀態碼和 JSON，不轉址 —— 預設的轉址是給伺服器畫頁面用的，SPA 用不到
                .formLogin(login -> login
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                        // 不分「沒有這個帳號」還是「密碼錯」：分開說等於告訴外人哪些帳號存在
                        .failureHandler((request, response, e) -> json(response, HttpStatus.UNAUTHORIZED, "帳號或密碼錯誤"))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // SPA 用的 CSRF 設定（Spring Security 6.4 起）：token 放在 XSRF-TOKEN cookie（JavaScript 讀得到），
                // 前端送 POST、DELETE 時從 cookie 讀出來、放進 X-XSRF-TOKEN header
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> json(response, HttpStatus.UNAUTHORIZED, "請先登入"))
                        // 已登入但被拒絕：這裡實際上只會是 CSRF token 沒帶或過期
                        .accessDeniedHandler((request, response, e) -> json(response, HttpStatus.FORBIDDEN, "請重新整理頁面再試一次")));
        return http.build();
    }

    // 錯誤回應的格式跟各 controller 一樣是 {"error": "..."}，前端用同一套方式顯示
    private static void json(HttpServletResponse response, HttpStatus status, String error) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
