package io.github.andersonchen.talkcal.calendar.adapter.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 兩道保護的行為：該打開的時候打開、不該打開的時候不打開（反例）、恢復後關回來、滿載時立刻拒絕。
 *
 * 門檻用跟 application.properties 一樣的形狀（10 次裡至少 5 次、一半失敗），只有時間縮短，測試才不必等 30 秒。
 */
@DisplayName("GuardedChatModel")
class GuardedChatModelTest {

    private static final Duration OPEN_FOR = Duration.ofMillis(200);
    private static final Duration RETRY_AFTER_OPEN = Duration.ofSeconds(30);
    private static final Duration RETRY_AFTER_BUSY = Duration.ofSeconds(5);
    private static final Prompt PROMPT = new Prompt("明天有什麼行程？");

    /** 假模型：照 behavior 決定這一次成功、失敗還是卡住；數自己被叫了幾次，才看得出「被擋下來的沒有叫到模型」。 */
    static final class FakeModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Runnable> behavior = new AtomicReference<>(() -> { });

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            behavior.get().run();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("明天沒有行程"))));
        }

        void fails(RuntimeException e) {
            behavior.set(() -> { throw e; });
        }

        void works() {
            behavior.set(() -> { });
        }
    }

    private final FakeModel model = new FakeModel();
    private final CircuitBreaker breaker = CircuitBreaker.of("chat-model", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofMillis(100))
            .slowCallRateThreshold(50)
            .waitDurationInOpenState(OPEN_FOR)
            .permittedNumberOfCallsInHalfOpenState(2)
            .recordException(GuardedChatModel::countsAgainstModel)
            .build());

    private GuardedChatModel guarded(int maxConcurrentCalls) {
        Bulkhead bulkhead = Bulkhead.of("chat-model", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls).maxWaitDuration(Duration.ZERO).build());
        return new GuardedChatModel(model, breaker, bulkhead, RETRY_AFTER_OPEN, RETRY_AFTER_BUSY);
    }

    private final GuardedChatModel guarded = guarded(50);

    private static RuntimeException connectionRefused() {
        return new OpenAIIoException("Connection refused", null);
    }

    private void callIgnoringFailure() {
        try {
            guarded.call(PROMPT);
        } catch (RuntimeException ignored) {
            // 這裡只在乎 breaker 記了什麼
        }
    }

    @Test
    @DisplayName("反例：模型一直正常，叫再多次都不會打開")
    void staysClosedWhileHealthy() {
        for (int i = 0; i < 50; i++) {
            assertThat(guarded.call(PROMPT).getResult().getOutput().getText()).isEqualTo("明天沒有行程");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(model.calls).hasValue(50);
    }

    @Test
    @DisplayName("反例：偶爾失敗一次（10 次裡 1 次）不會打開")
    void occasionalFailureDoesNotOpen() {
        for (int i = 0; i < 30; i++) {
            if (i % 10 == 0) {
                model.fails(connectionRefused());
            } else {
                model.works();
            }
            callIgnoringFailure();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("模型連不上：失敗 5 次就打開，之後不再叫模型、立刻丟 MODEL_UNAVAILABLE（CIRCUIT_OPEN）")
    void opensAfterRepeatedFailures() {
        model.fails(connectionRefused());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guarded.call(PROMPT))
                    .as("還沒打開時，原本的失敗原樣往上丟")
                    .isInstanceOf(OpenAIIoException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> guarded.call(PROMPT))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.reason()).isEqualTo(ModelUnavailableException.Reason.CIRCUIT_OPEN);
                        assertThat(e.retryAfter()).isEqualTo(RETRY_AFTER_OPEN);
                    })
                    // 舊的契約照樣成立：沒認得它的呼叫端當成一般的失敗
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(model.calls).as("打開之後一次都沒叫到模型").hasValue(5);
    }

    @Test
    @DisplayName("模型回 5xx 也算失敗")
    void serverErrorsCount() {
        model.fails(InternalServerException.builder().statusCode(500).headers(Headers.builder().build()).build());
        for (int i = 0; i < 5; i++) {
            callIgnoringFailure();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("反例：400（這個請求本身有問題，例如對話太長）不算模型出事，再多次都不會打開")
    void badRequestsDoNotCount() {
        BadRequestException tooLong = BadRequestException.builder().headers(Headers.builder().build()).build();
        model.fails(tooLong);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> guarded.call(PROMPT)).isSameAs(tooLong);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 包了一層也認得出來
        assertThat(GuardedChatModel.countsAgainstModel(new RuntimeException("包一層", tooLong))).isFalse();
        assertThat(GuardedChatModel.countsAgainstModel(new RuntimeException("包一層", connectionRefused()))).isTrue();
    }

    @Test
    @DisplayName("模型沒死但太慢（超過門檻）：一半以上都慢就打開")
    void opensWhenTooSlow() {
        model.behavior.set(() -> sleep(150));
        for (int i = 0; i < 5; i++) {
            guarded.call(PROMPT);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("模型恢復：打開的時間過了，放兩個去試，都成功就關回來")
    void closesAfterRecovery() {
        model.fails(connectionRefused());
        for (int i = 0; i < 5; i++) {
            callIgnoringFailure();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        model.works();
        assertThatThrownBy(() -> guarded.call(PROMPT)).as("時間還沒到，照樣擋").isInstanceOf(ModelUnavailableException.class);
        sleep(OPEN_FOR.toMillis() + 100);

        guarded.call(PROMPT);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        guarded.call(PROMPT);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("還沒恢復：去試的又失敗，就再打開一次")
    void reopensWhenProbeFails() {
        model.fails(connectionRefused());
        for (int i = 0; i < 5; i++) {
            callIgnoringFailure();
        }
        sleep(OPEN_FOR.toMillis() + 100);

        callIgnoringFailure();
        callIgnoringFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("同時在等模型的滿了：下一個立刻丟 MODEL_BUSY，不等也不叫模型；這不算模型失敗，breaker 不受影響")
    void rejectsWhenFull() throws Exception {
        GuardedChatModel oneAtATime = guarded(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.behavior.set(() -> {
            entered.countDown();
            await(release);
        });
        CompletableFuture<ChatResponse> stuck = CompletableFuture.supplyAsync(() -> oneAtATime.call(PROMPT));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> oneAtATime.call(PROMPT))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(ModelUnavailableException.Reason.BUSY);
                    assertThat(e.retryAfter()).isEqualTo(RETRY_AFTER_BUSY);
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("立刻拒絕，不排隊").isLessThan(Duration.ofMillis(100));
        assertThat(model.calls).hasValue(1);

        release.countDown();
        stuck.get(5, TimeUnit.SECONDS);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 位子空出來之後又收得進去
        model.works();
        oneAtATime.call(PROMPT);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
