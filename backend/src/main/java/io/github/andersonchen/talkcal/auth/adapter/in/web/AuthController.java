package io.github.andersonchen.talkcal.auth.adapter.in.web;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.UsernameTakenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帳號的 HTTP 入口：註冊、看「目前是誰」、有哪些登入方式。
 *
 * 登入、登出不在這裡：交給 Spring Security 的 filter（POST /api/auth/login、/api/auth/logout，
 * Google、GitHub 的 /api/auth/oauth2/*，設定在 app.config），
 * 它們會處理 session、CSRF token 換新、防 session fixation 這些細節，自己寫 controller 很容易漏掉。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth", description = "帳號：註冊、目前登入的是誰、有哪些登入方式（登入、登出是 Spring Security 的 /api/auth/login、/api/auth/logout）")
public final class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RegisterAccountUseCase registerAccount;
    private final FindAccountUseCase findAccount;
    private final LoginProviders loginProviders;

    public AuthController(RegisterAccountUseCase registerAccount, FindAccountUseCase findAccount,
                          LoginProviders loginProviders) {
        this.registerAccount = Objects.requireNonNull(registerAccount, "registerAccount 不可為 null");
        this.findAccount = Objects.requireNonNull(findAccount, "findAccount 不可為 null");
        this.loginProviders = Objects.requireNonNull(loginProviders, "loginProviders 不可為 null");
    }

    /** 註冊用的帳密。 */
    public record RegisterRequest(
            @Schema(description = "帳號名稱：3～32 個字，英數、底線、點、減號（不分大小寫）", example = "amy") String username,
            @Schema(description = "密碼：8～72 bytes（中文一個字算 3）", example = "correct-horse") String password) {
    }

    /** 目前登入的是誰。 */
    public record Me(
            @Schema(description = "帳號 ID：行程、對話的擁有者就是它") String id,
            @Schema(description = "給人看的名字：帳密登入的是帳號名稱，Google、GitHub 登入的是對方給的名字") String name,
            @Schema(description = "帳號名稱；只用 Google、GitHub 登入的帳號沒有，是 null", nullable = true) String username) {
    }

    /** 除了帳密之外還能怎麼登入。 */
    public record Providers(
            @Schema(description = "開了的登入方式，前端照這個畫按鈕；按鈕連到 /api/auth/oauth2/authorization/{id}",
                    example = "[\"google\", \"github\"]") List<String> providers) {
    }

    /** 失敗時的回應；欄位名稱跟 calendar 的錯誤一樣是 error，前端用同一套方式顯示。 */
    public record AuthFailure(String error) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "註冊帳號（不會自動登入，註冊完要再呼叫 /api/auth/login）")
    public Me register(@RequestBody RegisterRequest request) {
        AccountId id = registerAccount.register(request.username(), request.password());
        // 回傳的名字照資料庫裡存的（轉過小寫），不是使用者打的原樣
        return me(id).orElseThrow(() -> new IllegalStateException("剛註冊的帳號查不到：" + id));
    }

    @GetMapping("/providers")
    @Operation(summary = "除了帳密之外還能怎麼登入（沒登入也能問）；沒設定 Google、GitHub 的 client id 時是空陣列")
    public Providers providers() {
        return new Providers(loginProviders.ids());
    }

    @GetMapping("/me")
    @Operation(summary = "目前登入的是誰；沒登入是 401")
    public ResponseEntity<Me> me(Authentication authentication) {
        // 沒登入的請求進不到這裡（SecurityFilterChain 先回 401）。
        // 但 session 還在、帳號卻已經不在了（例如被刪掉）時，也當成沒登入
        return me(AccountId.of(authentication.getName()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private Optional<Me> me(AccountId id) {
        return findAccount.byId(id).map(AuthController::toMe);
    }

    private static Me toMe(Account account) {
        String username = account.password().map(login -> login.username().value()).orElse(null);
        return new Me(account.id().toString(), account.displayName(), username);
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<AuthFailure> onUsernameTaken(UsernameTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new AuthFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AuthFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("帳號請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new AuthFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AuthFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("帳號的資料庫操作失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new AuthFailure("系統暫時無法處理，請稍後再試"));
    }
}
