package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把行程存進 PostgreSQL、讀出來（表的定義在 db/migration/V2__create_calendar_events.sql，
 * 讀取用的索引在 V3__index_calendar_event_period.sql，分類、地點、備註是 V5 加的）。
 *
 * 寫法跟 conversation 的 JdbcConversationAdapter 一致：JdbcClient 手寫 SQL、
 * 交易用 TransactionTemplate（類別是 final，@Transactional 的代理包不了）。
 *
 * 一個類別實作讀、存、刪三個 port：碰的是同一張表，「這張表長什麼樣」的知識只該在一個地方。
 */
public final class JdbcCalendarEventAdapter implements SaveEventsPort, LoadEventsPort, DeleteEventPort {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcCalendarEventAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public void save(List<ScheduledEvent> events) {
        try {
            // port 的約定是「全部存或全部不存」：包在一個交易裡，任何一筆失敗，前面插入的也一起 rollback
            transaction.executeWithoutResult(status -> events.forEach(this::insert));
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存行程失敗（" + events.size() + " 筆）", e);
        }
    }

    @Override
    public List<ScheduledEvent> load(DateRange range) {
        List<Row> rows;
        try {
            // 條件寫成 tsrange(start_at, end_at) && tsrange(...)，跟 V3 索引的運算式一字不差：
            // 規劃器是拿「運算式長得一不一樣」來判斷能不能用運算式索引的，
            // 寫成等價的 start_at < :end AND end_at > :start 結果一樣，但就用不到那個 GiST 索引了。
            // ORDER BY 多帶 end_at、id：開始時間相同時順序也固定，畫面不會每次重新整理就換位置
            rows = jdbc.sql("""
                            SELECT id, title, start_at, end_at, category, location, note
                            FROM calendar_event
                            WHERE tsrange(start_at, end_at) && tsrange(:start, :end)
                            ORDER BY start_at, end_at, id
                            """)
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
    public boolean delete(EventId id) {
        try {
            // 刪到 0 列就是沒有這筆；「沒有算不算錯」交給 use case 判斷
            return jdbc.sql("DELETE FROM calendar_event WHERE id = :id")
                    .param("id", id.value())
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

    private void insert(ScheduledEvent scheduled) {
        jdbc.sql("""
                        INSERT INTO calendar_event (id, title, start_at, end_at, category, location, note)
                        VALUES (:id, :title, :start, :end, :category, :location, :note)
                        """)
                .param("id", scheduled.id().value())
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
