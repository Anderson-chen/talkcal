package io.github.andersonchen.talkcal.quota.adapter.in.web;

import io.github.andersonchen.talkcal.quota.application.port.in.QuotaExceededException;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 QuotaInterceptor 在每日次數用完時丟的 QuotaExceededException 轉成 429，套用在每個 controller 上。
 * 格式跟各 controller 的錯誤一樣（error、code、retryAfterSeconds），前端照 code 判斷要不要提示註冊。
 */
@RestControllerAdvice
public final class QuotaExceededAdvice {

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<QuotaFailure> onQuotaExceeded(QuotaExceededException e) {
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new QuotaFailure(e.getMessage(), "QUOTA_EXCEEDED", seconds));
    }

    public record QuotaFailure(
            @Schema(description = "給人看的說明，來自規則表") String error,
            @Schema(description = "給程式判斷的錯誤碼", allowableValues = {"QUOTA_EXCEEDED"}) String code,
            @Schema(description = "離換日還有幾秒，跟 Retry-After 標頭一樣") long retryAfterSeconds) {
    }
}
