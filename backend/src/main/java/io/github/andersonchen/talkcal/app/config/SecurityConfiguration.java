package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountOAuth2UserService;
import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountOidcUserService;
import io.github.andersonchen.talkcal.auth.adapter.in.security.AccountUserDetailsService;
import io.github.andersonchen.talkcal.auth.adapter.in.web.LoginProviders;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * 帳密登入，加上有設定 client id 的 Google、GitHub 登入。
 *
 * 瀏覽器和後端之間只有一個 HttpOnly 的 session cookie，前端不碰任何 token；Google 給的 token 也只留在後端的 session。
 * 前端和後端在同一個網域（nginx 把 /api 轉過來），不需要 CORS。
 *
 * - /api 底下除了註冊、登入都要登入；沒登入回 401 JSON，不轉址到登入頁，登入畫面由前端畫
 * - POST、PUT、DELETE 要帶 CSRF token：別的網站騙瀏覽器送請求時 cookie 會自動帶上，
 *   但別的網站讀不到這個網域的 XSRF-TOKEN cookie，送不出對的 header
 * - API 文件不擋：nginx 只轉 /api，外面連不到
 * - actuator 不擋：actuator 在不發佈到主機的 management 埠，連不連得到由網路決定，Alloy 抓指標不必登入。
 *   用 EndpointRequest，不寫死 /actuator/**：EndpointRequest 只比對 management 埠上的請求，在 API 埠上請求 /actuator 照樣要登入
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    // 沒有程式注入這個 bean：Spring Security 登入時自己從 context 拿
    @Bean
    UserDetailsService userDetailsService(FindAccountUseCase findAccount) {
        return new AccountUserDetailsService(findAccount);
    }

    @Bean
    OAuth2Registrations oauth2Registrations(
            @Value("${talkcal.auth.google.client-id:}") String googleClientId,
            @Value("${talkcal.auth.google.client-secret:}") String googleClientSecret,
            @Value("${talkcal.auth.github.client-id:}") String githubClientId,
            @Value("${talkcal.auth.github.client-secret:}") String githubClientSecret) {
        return OAuth2Registrations.of(googleClientId, googleClientSecret, githubClientId, githubClientSecret);
    }

    // 跟實際開了的 provider 從同一份 registrations 來，前端畫的按鈕不會多也不會少
    @Bean
    LoginProviders loginProviders(OAuth2Registrations oauth2Registrations) {
        return new LoginProviders(oauth2Registrations.ids());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OAuth2Registrations oauth2Registrations,
                                            SignInExternallyUseCase signInExternally) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // 畫登入頁之前要先取得有哪些按鈕，這時還沒登入
                        .requestMatchers(HttpMethod.GET, "/api/auth/providers").permitAll()
                        // 出錯時 Spring Boot 會轉到 /error 產生回應；擋住的話，所有錯誤都變成 401
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                // 成功、失敗都只回狀態碼和 JSON，不轉址：預設的轉址是給伺服器畫頁面用的
                .formLogin(login -> login
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                        // 不分「沒有這個帳號」和「密碼錯」：分開說等於告訴外人哪些帳號存在
                        .failureHandler((request, response, e) -> json(response, HttpStatus.UNAUTHORIZED, "帳號或密碼錯誤"))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // token 放在 XSRF-TOKEN cookie（JavaScript 讀得到），前端送出前讀出來放進 X-XSRF-TOKEN header
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(errors -> errors
                        // 開了 OAuth2 登入之後，預設會把沒登入的請求轉址到 provider 的登入頁，
                        // 前端的 fetch 會跟著轉址、拿到一頁 Google 的 HTML
                        .authenticationEntryPoint((request, response, e) -> json(response, HttpStatus.UNAUTHORIZED, "請先登入"))
                        // 已登入還被拒絕，實際上只會是 CSRF token 沒帶或過期
                        .accessDeniedHandler((request, response, e) -> json(response, HttpStatus.FORBIDDEN, "請重新整理頁面再試一次")));

        if (!oauth2Registrations.isEmpty()) {
            // 這個流程是整頁轉址，不是 fetch：
            // 1. 前端的按鈕連到 /api/auth/oauth2/authorization/google → 轉址到 Google 的登入頁（帶著 state，防 CSRF）
            // 2. 使用者在 Google 同意 → Google 轉回 /api/auth/oauth2/callback/google?code=...
            // 3. 後端拿 code 換 token、驗 ID token，對到帳號 → 轉回首頁，這時已經登入
            http.oauth2Login(oauth2 -> oauth2
                    .clientRegistrationRepository(new InMemoryClientRegistrationRepository(oauth2Registrations.all()))
                    // access token 放在 session，登出、過期就跟著消失。
                    // 預設是放在 app 的記憶體裡、以使用者為 key，每個登入過的人都留一份、永遠不清
                    .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                    // nginx 只把 /api 轉給 app
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(OAuth2Registrations.AUTHORIZATION_BASE_URI))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(OAuth2Registrations.CALLBACK_BASE_URI))
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(new AccountOidcUserService(signInExternally))
                            .userService(new AccountOAuth2UserService(signInExternally)))
                    // 整頁轉址，回 JSON 的話使用者會看到一頁 JSON。
                    // 失敗原因只記 log、不放進網址：網址會留在瀏覽紀錄裡，也不該讓使用者看到內部錯誤
                    .successHandler((request, response, authentication) -> response.sendRedirect("/"))
                    .failureHandler((request, response, e) -> {
                        log.warn("Google／GitHub 登入失敗：{}", e.getMessage(), e);
                        response.sendRedirect("/?login_error=oauth");
                    }));
        }
        return http.build();
    }

    // 跟各 controller 的錯誤回應同一個格式 {"error": "..."}，前端用同一套方式顯示
    private static void json(HttpServletResponse response, HttpStatus status, String error) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
