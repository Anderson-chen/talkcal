package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountOAuth2UserService;
import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountOidcUserService;
import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountUserDetailsService;
import io.github.andersonchen.talkcal.auth.adapter.in.web.LoginProviders;
import io.github.andersonchen.talkcal.auth.adapter.out.security.SpringPasswordHasher;
import io.github.andersonchen.talkcal.auth.application.domain.service.RegisterAccountService;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * 本機帳密登入（talkcal.auth.provider=local，也是沒設定時的預設），加上有設定的 Google、GitHub 登入（#105）。
 *
 * 瀏覽器和後端之間只有一個 session cookie（HttpOnly，JavaScript 讀不到），前端不碰任何 token ——
 * 用 Google 登入時也一樣：Google 給的 token 留在後端的 session 裡，換完使用者資料就沒再用。
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

    private static final Logger log = LoggerFactory.getLogger(LocalAuthConfiguration.class);

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

    /**
     * 有設定 client id 的 Google、GitHub。值寫在 application.properties，再從環境變數 GOOGLE_CLIENT_ID 這類名字接進來，
     * 沒設就是空字串 → 那個 provider 不開。
     */
    @Bean
    OAuth2Registrations oauth2Registrations(
            @Value("${talkcal.auth.google.client-id:}") String googleClientId,
            @Value("${talkcal.auth.google.client-secret:}") String googleClientSecret,
            @Value("${talkcal.auth.github.client-id:}") String githubClientId,
            @Value("${talkcal.auth.github.client-secret:}") String githubClientSecret) {
        return OAuth2Registrations.of(googleClientId, googleClientSecret, githubClientId, githubClientSecret);
    }

    // 前端問 /api/auth/providers 拿到的清單，跟實際開了的 provider 一定一致：都從同一份 registrations 來
    @Bean
    LoginProviders loginProviders(OAuth2Registrations oauth2Registrations) {
        return new LoginProviders(oauth2Registrations.ids());
    }

    @Bean
    SecurityFilterChain localSecurity(HttpSecurity http, OAuth2Registrations oauth2Registrations,
                                      SignInExternallyUseCase signInExternally) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // 登入頁要先知道有哪些按鈕可以畫，這時還沒登入
                        .requestMatchers(HttpMethod.GET, "/api/auth/providers").permitAll()
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
                        // 明寫 entry point：開了 OAuth2 登入之後，Spring Security 預設會把沒登入的請求轉址到 provider 的登入頁，
                        // 前端的 fetch 會跟著轉址、拿到一頁 Google 的 HTML。API 要的是 401
                        .authenticationEntryPoint((request, response, e) -> json(response, HttpStatus.UNAUTHORIZED, "請先登入"))
                        // 已登入但被拒絕：這裡實際上只會是 CSRF token 沒帶或過期
                        .accessDeniedHandler((request, response, e) -> json(response, HttpStatus.FORBIDDEN, "請重新整理頁面再試一次")));

        if (!oauth2Registrations.isEmpty()) {
            // 用 Google、GitHub 登入（#105）。跟帳密登入不同，整個流程是整頁轉址，不是 fetch：
            // 1. 前端的按鈕連到 /api/auth/oauth2/authorization/google → 轉址到 Google 的登入頁（帶著 state，防 CSRF）
            // 2. 使用者在 Google 同意 → Google 轉回 /api/auth/oauth2/callback/google?code=...
            // 3. 後端拿 code 換 token、驗 ID token，交給 AccountOidcUserService 對到帳號 → 轉回首頁，這時已經登入
            http.oauth2Login(oauth2 -> oauth2
                    .clientRegistrationRepository(new InMemoryClientRegistrationRepository(oauth2Registrations.all()))
                    // Google 給的 access token 放在 session，登出、過期就跟著消失。
                    // 預設是放在 app 的記憶體裡、以使用者為 key，每個登入過的人都留一份、永遠不清
                    .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                    // 路徑都放在 /api 底下：nginx 只把 /api 轉給 app
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(OAuth2Registrations.AUTHORIZATION_BASE_URI))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(OAuth2Registrations.CALLBACK_BASE_URI))
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(new AccountOidcUserService(signInExternally))
                            .userService(new AccountOAuth2UserService(signInExternally)))
                    // 成功、失敗都轉回前端的首頁（這是整頁轉址，回 JSON 的話使用者會看到一頁 JSON）。
                    // 失敗的原因只記 log、不放進網址：網址會留在瀏覽紀錄裡，也不該讓使用者看到內部錯誤
                    .successHandler((request, response, authentication) -> response.sendRedirect("/"))
                    .failureHandler((request, response, e) -> {
                        log.warn("Google／GitHub 登入失敗：{}", e.getMessage(), e);
                        response.sendRedirect("/?login_error=oauth");
                    }));
        }
        return http.build();
    }

    // 錯誤回應的格式跟各 controller 一樣是 {"error": "..."}，前端用同一套方式顯示
    private static void json(HttpServletResponse response, HttpStatus status, String error) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
