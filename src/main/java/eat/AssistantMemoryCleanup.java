package eat;

import eat.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 每天清一次 AI 助理的舊對話：超過保留期限沒再說過話的，整段刪掉。
 *
 * 為什麼要清：每段對話都存著工具呼叫和工具結果（一輪好幾列），前端只記最新那一段的 id，
 * 按「清空」或換瀏覽器之後，舊的那段就沒人接得回來了，只會一直留在表裡。
 *
 * 為什麼放在組裝根、不放在 adapter.in：計時器也算是「從外面推進來」的入口，
 * 但它要直接用 JdbcAssistantMemoryRepository（不是 port，是這張表自己的維護工作），
 * 而 ArchitectureTest 只准組裝根認識 adapter.out.persistence。
 * C 重構把助理搬成自己的模組時，這裡跟著改成呼叫那邊的 use case。
 */
final class AssistantMemoryCleanup {

    private static final Logger log = LoggerFactory.getLogger(AssistantMemoryCleanup.class);

    private final JdbcAssistantMemoryRepository memory;
    private final Duration retention;
    private final Clock clock;

    AssistantMemoryCleanup(JdbcAssistantMemoryRepository memory, Duration retention, Clock clock) {
        this.memory = Objects.requireNonNull(memory, "memory 不可為 null");
        this.retention = Objects.requireNonNull(retention, "retention 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        if (retention.isNegative() || retention.isZero()) {
            // 0 會把正在聊的那段也刪掉
            throw new IllegalArgumentException("保留期限要大於 0：" + retention);
        }
    }

    // 每天凌晨四點（台北時間）：沒人在用的時候。多台一起跑也沒關係，DELETE 重跑一次只是刪 0 段
    @Scheduled(cron = "0 0 4 * * *", zone = "${calendar.zone}")
    void run() {
        int deleted = memory.deleteInactiveSince(clock.instant().minus(retention));
        log.info("清掉 AI 助理的舊對話：{} 段（超過 {} 天沒說話）", deleted, retention.toDays());
    }
}
