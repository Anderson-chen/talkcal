package io.github.andersonchen.talkcal.app.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.RustFsContainer;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantProposalAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AI 助理對話的歸檔（#54）：真的 PostgreSQL、真的 S3 相容儲存（RustFS）。
 *
 * 跟行程那邊（EventArchiveFlowTest）同一組規則、同一組反例：
 * 存好才刪 ↔ 上傳失敗一列都不少；歸檔 → 還原後讀出來一樣；只還原得到自己的；不舊的不搬。
 */
@Testcontainers
@DisplayName("AssistantMemoryArchive：PostgreSQL ↔ S3 相容儲存")
class AssistantMemoryArchiveTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @Container
    static final RustFsContainer RUSTFS = new RustFsContainer();

    private static final OwnerId ALICE = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000a");
    private static final OwnerId BOB = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000b");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    // 超過 30 天沒說話的對話「最後說話」的時間
    private static final Instant MARCH_2026 = Instant.parse("2026-03-15T02:00:00Z");

    static JdbcClient jdbc;
    static JdbcAssistantMemoryRepository memory;
    static ArchiveBucket bucket;
    static AssistantMemoryArchive archive;

    private final String id = UUID.randomUUID().toString();

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        memory = new JdbcAssistantMemoryRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        RUSTFS.createBucket("talkcal-archive");
        bucket = RUSTFS.bucket("talkcal-archive");
        archive = new AssistantMemoryArchive(memory, new JdbcAssistantProposalAdapter(jdbc), bucket, Duration.ofDays(30), Clock.system(TAIPEI));
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
        archive.deleteAll(ALICE);
        archive.deleteAll(BOB);
    }

    private static List<Message> oneTurnWithTool() {
        return List.of(
                new UserMessage("晚上"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "propose_events",
                                "{\"description\":\"明天晚上七點吃飯\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "propose_events", "已顯示 1 張卡片")))
                        .build(),
                new AssistantMessage("幫你排好了，確認一下卡片"));
    }

    // 把某人某段對話的時間整段改到 lastActive（模擬「那時候聊的」）
    private static void lastSpokeAt(OwnerId owner, String conversationId, Instant lastActive) {
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = :at WHERE owner_id = :owner AND conversation_id = :id")
                .param("at", OffsetDateTime.ofInstant(lastActive, ZoneOffset.UTC))
                .param("owner", owner.value()).param("id", UUID.fromString(conversationId)).update();
    }

    private static Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(30));
    }


    @Test
    @DisplayName("舊的搬走、最近的留著；歸檔 → 還原後讀出來跟原本一樣（連工具呼叫）")
    void archiveThenRestore() {
        ChatMemoryRepository alices = memory.of(ALICE);
        String recent = UUID.randomUUID().toString();
        alices.saveAll(id, oneTurnWithTool());
        alices.saveAll(recent, List.of(new UserMessage("剛剛")));
        lastSpokeAt(ALICE, id, MARCH_2026);
        List<Message> before = alices.findByConversationId(id);

        assertEquals(new AssistantMemoryArchive.Report(1, 0), archive.archiveInactiveSince(cutoff()));
        assertTrue(alices.findByConversationId(id).isEmpty());
        assertEquals(1, alices.findByConversationId(recent).size());
        assertEquals(List.of("assistant-conversation/owner=" + ALICE + "/month=2026-03/" + id + ".jsonl.gz"),
                bucket.list("assistant-conversation/owner=" + ALICE + "/"));

        assertEquals(1, archive.restore(ALICE, new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3))));
        assertEquals(before.toString(), alices.findByConversationId(id).toString());
        // 再還原一次：已經在了，跳過
        assertEquals(0, archive.restore(ALICE, new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3))));
        assertEquals(4, alices.findByConversationId(id).size());
    }

    @Test
    @DisplayName("反例：物件儲存掛了（bucket 不存在）→ 一列都不刪，對話完整留在資料庫")
    void uploadFailureDeletesNothing() {
        memory.of(ALICE).saveAll(id, oneTurnWithTool());
        lastSpokeAt(ALICE, id, MARCH_2026);
        AssistantMemoryArchive broken = new AssistantMemoryArchive(memory, new JdbcAssistantProposalAdapter(jdbc), RUSTFS.bucket("no-such-bucket"),
                Duration.ofDays(30), Clock.system(TAIPEI));

        assertEquals(new AssistantMemoryArchive.Report(0, 1), broken.archiveInactiveSince(cutoff()));
        assertEquals(4, memory.of(ALICE).findByConversationId(id).size());
    }

    @Test
    @DisplayName("不同使用者不混：同一個 conversationId、同一個月，Bob 還原只拿回 Bob 的，碰不到 Alice 的")
    void restoreOnlyReturnsOwnersConversations() {
        memory.of(ALICE).saveAll(id, oneTurnWithTool());
        memory.of(BOB).saveAll(id, List.of(new UserMessage("Bob 的")));
        lastSpokeAt(ALICE, id, MARCH_2026);
        lastSpokeAt(BOB, id, MARCH_2026);
        assertEquals(new AssistantMemoryArchive.Report(2, 0), archive.archiveInactiveSince(cutoff()));

        assertEquals(1, archive.restore(BOB, new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3))));
        assertEquals("Bob 的", memory.of(BOB).findByConversationId(id).getFirst().getText());
        assertTrue(memory.of(ALICE).findByConversationId(id).isEmpty());
    }

    @Test
    @DisplayName("刪掉一個人的全部歸檔（給 #52 刪帳號用）：另一個人的還在")
    void deleteAllRemovesOnlyThatOwner() {
        memory.of(ALICE).saveAll(id, oneTurnWithTool());
        memory.of(BOB).saveAll(id, List.of(new UserMessage("Bob 的")));
        lastSpokeAt(ALICE, id, MARCH_2026);
        lastSpokeAt(BOB, id, MARCH_2026);
        archive.archiveInactiveSince(cutoff());

        assertEquals(1, archive.deleteAll(ALICE));

        assertEquals(0, archive.restore(ALICE, new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3))));
        assertEquals(1, archive.restore(BOB, new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3))));
    }

    @Test
    @DisplayName("月份用台北時間算：UTC 2 月底、台北已經 3 月 1 日的對話，放在 3 月")
    void monthFollowsCalendarZone() {
        memory.of(ALICE).saveAll(id, List.of(new UserMessage("月初")));
        lastSpokeAt(ALICE, id, Instant.parse("2026-02-28T17:00:00Z"));

        archive.archiveInactiveSince(cutoff());

        assertEquals(1, bucket.list("assistant-conversation/owner=" + ALICE + "/month=2026-03/").size());
    }
}
