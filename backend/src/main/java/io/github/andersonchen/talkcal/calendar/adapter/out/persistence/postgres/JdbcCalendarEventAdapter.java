package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 把行程存進 PostgreSQL、讀出來（表的定義在 db/migration/V2__create_calendar_events.sql，
 * 讀取用的索引在 V3__index_calendar_event_period.sql，分類、地點、備註是 V5 加的，擁有者是 V11 加的，
 * 冪等鍵的表 calendar_add_request 是 V12 加的）。
 *
 * 每一句 SQL 都帶著 owner_id 條件（#104）：授權不是另外一道「先查再比對」的檢查，而是查詢本身就只碰得到這個人的資料。
 *
 * 寫法跟 conversation 的 JdbcConversationAdapter 一致：JdbcClient 手寫 SQL、
 * 交易用 TransactionTemplate（類別是 final，@Transactional 的代理包不了）。
 *
 * 一個類別實作讀、存、刪幾個 port：碰的是同一張表，「這張表長什麼樣」的知識只該在一個地方。
 * 冪等鍵的表也在這裡：記下的是「哪一批行程」，要跟 calendar_event 在同一個交易裡一起寫。
 */
public final class JdbcCalendarEventAdapter implements SaveEventsPort, SaveEventsOncePort, LoadEventsPort, DeleteEventPort {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcCalendarEventAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public void save(OwnerId owner, List<ScheduledEvent> events) {
        try {
            // port 的約定是「全部存或全部不存」：包在一個交易裡，任何一筆失敗，前面插入的也一起 rollback
            transaction.executeWithoutResult(status -> events.forEach(event -> insert(owner, event)));
        } catch (DataAccessException | TransactionException e) {
            // 連不上資料庫時，失敗發生在開交易那一步，丟的是 TransactionException，不是 DataAccessException
            throw new IllegalStateException("儲存行程失敗（" + events.size() + " 筆）", e);
        }
    }

    /**
     * 先搶下這個鍵、搶到了才存行程，兩件事在同一個交易裡（#51，表和理由見 V12）。
     *
     * 搶不到（之前有人用這個鍵存過）就不存，改看當時存的內容一不一樣：一樣就回當時那幾筆，不一樣就是 KeyUsedForOtherContent。
     * 兩個請求同時帶著同一個鍵進來時，後到的 INSERT ... ON CONFLICT 會停在那裡等先到的交易結束：
     * 先到的 commit 了，後到的就搶不到、讀得到先到存的那幾筆；先到的 rollback 了，後到的就搶到、自己存。
     */
    @Override
    public SaveOnceResult saveOnce(OwnerId owner, IdempotencyKey key, List<ScheduledEvent> events) {
        String hash = fingerprint(events);
        try {
            return transaction.execute(status -> {
                int claimed = jdbc.sql("""
                                INSERT INTO calendar_add_request (owner_id, idempotency_key, request_hash, event_ids)
                                VALUES (:owner, :key, :hash, CAST(:ids AS uuid[]))
                                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                                """)
                        .param("owner", owner.value())
                        .param("key", key.value())
                        .param("hash", hash)
                        // 陣列用 PostgreSQL 的文字寫法（{a,b}）傳進去再 CAST：UUID 裡沒有要跳脫的字元，不必動到 JDBC 的 Array
                        .param("ids", events.stream().map(e -> e.id().toString()).collect(Collectors.joining(",", "{", "}")))
                        .update();
                if (claimed == 1) {
                    events.forEach(event -> insert(owner, event));
                    return new Saved(events);
                }
                String earlierHash = jdbc.sql("""
                                SELECT request_hash FROM calendar_add_request
                                WHERE owner_id = :owner AND idempotency_key = :key
                                """)
                        .param("owner", owner.value())
                        .param("key", key.value())
                        .query(String.class)
                        .single();
                if (!earlierHash.equals(hash)) {
                    return new KeyUsedForOtherContent();
                }
                return new AlreadySaved(addedBy(owner, key));
            });
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("儲存行程失敗（" + events.size() + " 筆，Idempotency-Key " + key + "）", e);
        }
    }

    // 這個鍵當時存的那幾筆，照當時的順序；後來刪掉的就不在了（JOIN 不到）
    private List<ScheduledEvent> addedBy(OwnerId owner, IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT e.id, e.title, e.start_at, e.end_at, e.category, e.location, e.note
                        FROM calendar_add_request r
                        CROSS JOIN LATERAL unnest(r.event_ids) WITH ORDINALITY AS added(id, position)
                        JOIN calendar_event e ON e.id = added.id AND e.owner_id = r.owner_id
                        WHERE r.owner_id = :owner AND r.idempotency_key = :key
                        ORDER BY added.position
                        """)
                .param("owner", owner.value())
                .param("key", key.value())
                .query(JdbcCalendarEventAdapter::row)
                .list()
                .stream().map(JdbcCalendarEventAdapter::restore).toList();
    }

    /**
     * 清掉 before 之前記下的冪等鍵，回傳清了幾個（給 IdempotencyKeyCleanup 用）。
     * 不是 port：這是這張表自己的維護工作，跟 JdbcAssistantMemoryRepository.deleteInactiveSince 一樣由 app.job 直接呼叫。
     */
    public int deleteIdempotencyKeysCreatedBefore(Instant before) {
        try {
            return jdbc.sql("DELETE FROM calendar_add_request WHERE created_at < :before")
                    // TIMESTAMPTZ 對 OffsetDateTime：Instant 直接傳 PostgreSQL 的 driver 不認得（同 JdbcAssistantMemoryRepository）
                    .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException e) {
            throw new IllegalStateException("清除舊的冪等鍵失敗", e);
        }
    }

    /**
     * 一批行程的內容指紋（SHA-256，十六進位 64 字）：存進去的每個欄位都算，順序也算；id 不算（每次送都會重發）。
     *
     * 每個欄位寫成「長度:內容」、沒有值寫「-」再串起來：不靠分隔字元，標題裡有什麼字都不會讓兩批不同的內容串出一樣的字串。
     */
    static String fingerprint(List<ScheduledEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ScheduledEvent scheduled : events) {
            CalendarEvent event = scheduled.event();
            for (String field : new String[]{event.title(), event.start().toString(), event.end().toString(),
                    event.category().name(), event.location().orElse(null), event.note().orElse(null)}) {
                text.append(field == null ? "-" : field.length() + ":" + field).append(';');
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 每個 JVM 都一定要有 SHA-256（Java SE 規格），走不到這裡
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<ScheduledEvent> load(OwnerId owner, DateRange range) {
        List<Row> rows;
        try {
            // 條件寫成 owner_id = ... AND tsrange(start_at, end_at) && tsrange(...)，跟 V11 索引的兩個欄位對得上：
            // 規劃器是拿「運算式長得一不一樣」來判斷能不能用運算式索引的，
            // 期間寫成等價的 start_at < :end AND end_at > :start 結果一樣，但就用不到那個 GiST 索引了。
            // ORDER BY 多帶 end_at、id：開始時間相同時順序也固定，畫面不會每次重新整理就換位置
            rows = jdbc.sql("""
                            SELECT id, title, start_at, end_at, category, location, note
                            FROM calendar_event
                            WHERE owner_id = :owner AND tsrange(start_at, end_at) && tsrange(:start, :end)
                            ORDER BY start_at, end_at, id
                            """)
                    .param("owner", owner.value())
                    .param("start", range.startTime())
                    .param("end", range.endTime())
                    .query(JdbcCalendarEventAdapter::row)
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取行程失敗：" + range, e);
        }
        return rows.stream().map(JdbcCalendarEventAdapter::restore).toList();
    }

    @Override
    public boolean delete(OwnerId owner, EventId id) {
        try {
            // 刪到 0 列就是這個人沒有這筆（不存在、或是別人的）；「沒有算不算錯」交給 use case 判斷
            return jdbc.sql("DELETE FROM calendar_event WHERE id = :id AND owner_id = :owner")
                    .param("id", id.value())
                    .param("owner", owner.value())
                    .update() > 0;
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除行程失敗：" + id, e);
        }
    }

    /**
     * 讀出來的每一筆重新走一次 CalendarEvent 的規則，資料庫裡的壞資料在這裡就被擋下（跟 Conversation.restore 同一個想法）。
     * 擋下時丟 IllegalStateException 而不是讓 IllegalArgumentException 往外跑：
     * 壞的是資料庫裡的資料，不是呼叫端送錯東西，不該變成 400。
     */
    private static ScheduledEvent restore(Row row) {
        try {
            // Category.valueOf：資料庫裡的字串對不上 enum（例如 enum 改名了、有人手動塞怪值）會丟 IllegalArgumentException，
            // 跟其他規則違反一起翻成 IllegalStateException
            CalendarEvent event = new CalendarEvent(row.title(), row.start(), row.end(),
                    Category.valueOf(row.category()), Optional.ofNullable(row.location()), Optional.ofNullable(row.note()));
            return new ScheduledEvent(new EventId(row.id()), event);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("資料庫裡的行程 " + row.id() + " 不合規則：" + e.getMessage(), e);
        }
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        // getObject(..., LocalDateTime.class)：PostgreSQL 的 driver 把不帶時區的 TIMESTAMP 直接對應成 LocalDateTime，
        // 不像 getTimestamp() 會經過 JVM 預設時區
        return new Row(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class),
                rs.getString("category"), rs.getString("location"), rs.getString("note"));
    }

    private record Row(UUID id, String title, LocalDateTime start, LocalDateTime end,
                       String category, String location, String note) {
    }

    private void insert(OwnerId owner, ScheduledEvent scheduled) {
        jdbc.sql("""
                        INSERT INTO calendar_event (id, owner_id, title, start_at, end_at, category, location, note)
                        VALUES (:id, :owner, :title, :start, :end, :category, :location, :note)
                        """)
                .param("id", scheduled.id().value())
                .param("owner", owner.value())
                .param("title", scheduled.event().title())
                // LocalDateTime 直接交給 PostgreSQL 的 JDBC driver，它對應的正是不帶時區的 TIMESTAMP，不經過任何時區換算
                .param("start", scheduled.event().start())
                .param("end", scheduled.event().end())
                // 分類存 enum 的名稱，跟 V5 的 CHECK 一致；沒有地點、備註就存 NULL（V5 的 CHECK 不收空白字串）
                .param("category", scheduled.event().category().name())
                .param("location", scheduled.event().location().orElse(null))
                .param("note", scheduled.event().note().orElse(null))
                .update();
    }
}
