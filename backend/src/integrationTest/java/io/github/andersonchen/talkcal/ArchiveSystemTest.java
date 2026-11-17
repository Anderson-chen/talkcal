package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.RustFsContainer;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 整個 app 接上真的 PostgreSQL 和 RustFS。擁有者要從登入者一路帶到物件儲存的前綴，中間哪一層漏了，
 * 單獨測各層都會通過，只有這裡會失敗。
 *
 * 排程不等到凌晨，直接呼叫 app 裡同一個 ArchiveEventsUseCase、AssistantMemoryArchive。
 * 每一題註冊新的 amy、bob，帳號 id 不同，歸檔的前綴也不同，所以不必清物件儲存。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("系統測試：查看封存的行程")
class ArchiveSystemTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.newDatabase().register(registry);
    }

    @Container
    static final RustFsContainer RUSTFS = new RustFsContainer();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse";
    // 「現在」當成 2026-10-19，保留 2 年，2023 年的行程都算舊
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2024, 10, 19, 4, 30);
    private static final String MAY_1 = "/api/calendar/archive/events?from=2023-05-01&to=2023-05-01";

    @DynamicPropertySource
    static void archiveBucket(DynamicPropertyRegistry registry) {
        registry.add("talkcal.archive.endpoint", RUSTFS::endpoint);
        registry.add("talkcal.archive.region", () -> RustFsContainer.REGION);
        registry.add("talkcal.archive.access-key", () -> RustFsContainer.ACCESS_KEY);
        registry.add("talkcal.archive.secret-key", () -> RustFsContainer.SECRET_KEY);
    }

    // app 用的是 application.properties 的 talkcal.archive.bucket
    @BeforeAll
    static void createBucket() {
        RUSTFS.createBucket("talkcal-archive");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ArchiveEventsUseCase archiveEvents;

    @Autowired
    AssistantMemoryArchive conversationArchive;

    @Value("${calendar.zone}")
    ZoneId zone;

    private BrowserSession amy;
    private BrowserSession bob;

    @BeforeEach
    void twoPeople() throws Exception {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
        jdbc.sql("DELETE FROM account").update();
        amy = new BrowserSession(port).signUpAndLogIn("amy", PASSWORD);
        bob = new BrowserSession(port).signUpAndLogIn("bob", PASSWORD);
    }

    private static void addOldEvent(BrowserSession who, String title) throws Exception {
        HttpResponse<String> created = who.postJson("/api/calendar/events", """
                {"events": [{"title": "%s", "start": "2023-05-01T09:00:00", "end": "2023-05-01T10:00:00",
                             "category": "HEALTH", "location": "仁愛路診所", "note": "帶健保卡"}]}""".formatted(title));
        assertEquals(201, created.statusCode(), created.body());
    }

    private static JsonNode getJson(BrowserSession who, String path) throws Exception {
        HttpResponse<String> response = who.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static List<String> titles(JsonNode body) {
        return StreamSupport.stream(body.at("/events").spliterator(), false)
                .map(event -> event.at("/title").asString())
                .toList();
    }

    private long onlineRows() {
        return jdbc.sql("SELECT count(*) FROM calendar_event").query(Long.class).single();
    }

    @Test
    @DisplayName("amy、bob 封存了同一天的行程，各自查只看得到自己的，欄位跟封存前一樣；查完資料庫、月曆都沒有多出來")
    void eachSeesOnlyOwnArchiveAndNothingIsWritten() throws Exception {
        addOldEvent(amy, "amy 的牙醫");
        addOldEvent(bob, "bob 的牙醫");
        assertEquals(2, archiveEvents.archiveEndedBefore(CUTOFF).archived());
        assertEquals(0, onlineRows());

        JsonNode amys = getJson(amy, MAY_1);
        JsonNode bobs = getJson(bob, MAY_1);

        assertEquals(List.of("amy 的牙醫"), titles(amys));
        assertEquals(List.of("bob 的牙醫"), titles(bobs));
        JsonNode dentist = amys.at("/events/0");
        assertEquals("2023-05-01T09:00:00", dentist.at("/start").asString());
        assertEquals("2023-05-01T10:00:00", dentist.at("/end").asString());
        assertEquals("HEALTH", dentist.at("/category").asString());
        assertEquals("仁愛路診所", dentist.at("/location").asString());
        assertEquals("帶健保卡", dentist.at("/note").asString());

        // 查看不寫回資料庫，月曆上也不會出現
        assertEquals(0, onlineRows());
        assertEquals(0, getJson(amy, "/api/calendar/events?from=2023-04-24&to=2023-06-05").at("/events").size());
    }

    @Test
    @DisplayName("反例：起日晚於迄日、超過 24 個月 → 400")
    void invalidRangeIsBadRequest() throws Exception {
        assertEquals(400, amy.get("/api/calendar/archive/events?from=2023-05-05&to=2023-05-01").statusCode());
        assertEquals(400, amy.get("/api/calendar/archive/events?from=2021-01-01&to=2023-05-01").statusCode());
    }

    @Test
    @DisplayName("行程的還原拿掉之後，對話的還原照常可用：歸檔的對話放回來，讀得到原本的話")
    void conversationRestoreStillWorks() throws Exception {
        String amyId = getJson(amy, "/api/auth/me").at("/id").asString();
        String conversation = UUID.randomUUID().toString();
        // 不呼叫模型，直接照 JdbcAssistantMemoryRepository 存的格式寫一段 amy 的對話
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                        VALUES (:owner, :id, 0, 'USER', '明天幫我約牙醫'), (:owner, :id, 1, 'ASSISTANT', '早上還是下午？')
                        """)
                .param("owner", UUID.fromString(amyId))
                .param("id", UUID.fromString(conversation))
                .update();
        // 期限設在一分鐘後，剛寫的那段對話也會被當成之後沒再說話
        assertEquals(1, conversationArchive.archiveInactiveSince(Instant.now().plusSeconds(60)).archived());
        assertEquals(0, getJson(amy, "/api/calendar/assistant/" + conversation).at("/messages").size());

        YearMonth thisMonth = YearMonth.now(zone);
        HttpResponse<String> restored = amy.postJson(
                "/api/calendar/archive/restore?from=" + thisMonth.minusMonths(1) + "&to=" + thisMonth, "");

        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(1, JSON.readTree(restored.body()).at("/conversations").asInt());
        JsonNode messages = getJson(amy, "/api/calendar/assistant/" + conversation).at("/messages");
        assertEquals(2, messages.size());
        assertEquals("明天幫我約牙醫", messages.at("/0/text").asString());
    }
}
