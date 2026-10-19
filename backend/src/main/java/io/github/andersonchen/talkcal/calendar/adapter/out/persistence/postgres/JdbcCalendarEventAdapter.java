package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteArchivedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEndedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RestoreEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把行程存進 PostgreSQL、讀出來（表的定義在 db/migration/V2__create_calendar_events.sql，
 * 讀取用的索引在 V3__index_calendar_event_period.sql，分類、地點、備註是 V5 加的，擁有者是 V11 加的）。
 *
 * 每一句 SQL 都帶著 owner_id 條件（#104）：授權不是另外一道「先查再比對」的檢查，而是查詢本身就只碰得到這個人的資料。
 *
 * 寫法跟 conversation 的 JdbcConversationAdapter 一致：JdbcClient 手寫 SQL、
 * 交易用 TransactionTemplate（類別是 final，@Transactional 的代理包不了）。
 *
 * 一個類別實作讀、存、刪三個 port：碰的是同一張表，「這張表長什麼樣」的知識只該在一個地方。
 * 歸檔（#54）要的三個 port 也在這裡：找出舊的、歸檔好之後刪掉、從歸檔還原回來 —— 一樣是這張表。
 */
public final class JdbcCalendarEventAdapter implements SaveEventsPort, LoadEventsPort, DeleteEventPort,
        LoadEndedEventsPort, DeleteArchivedEventsPort, RestoreEventsPort {

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
     * 不帶 owner_id 條件：這是排程的維護工作，要看全部的人（LoadEndedEventsPort 的約定）。
     * 照 end_at、id 排：每一輪先搬最舊的，limit 截在哪裡也是固定的。
     * end_at 沒有自己的索引：一天跑一次，掃一次表比每次寫入都多維護一個索引便宜。表大到掃不動時再用一個 migration 加上
     */
    @Override
    public Map<OwnerId, List<ScheduledEvent>> loadEndedBefore(LocalDateTime cutoff, int limit) {
        List<OwnedRow> rows;
        try {
            rows = jdbc.sql("""
                            SELECT owner_id, id, title, start_at, end_at, category, location, note
                            FROM calendar_event
                            WHERE end_at < :cutoff
                            ORDER BY end_at, id
                            LIMIT :limit
                            """)
                    .param("cutoff", cutoff)
                    .param("limit", limit)
                    .query((rs, rowNum) -> new OwnedRow(new OwnerId(rs.getObject("owner_id", UUID.class)), row(rs, rowNum)))
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取要歸檔的行程失敗：" + cutoff, e);
        }
        Map<OwnerId, List<ScheduledEvent>> byOwner = new LinkedHashMap<>();
        rows.forEach(r -> byOwner.computeIfAbsent(r.owner(), o -> new ArrayList<>()).add(restore(r.row())));
        return byOwner;
    }

    @Override
    public int deleteArchived(OwnerId owner, Collection<EventId> ids, LocalDateTime cutoff) {
        if (ids.isEmpty()) {
            return 0;
        }
        try {
            // end_at < :cutoff 再檢查一次：讀出來到現在之間被改成未來的那筆，已經不舊了，不刪（它也沒必要留在歸檔，下次會再存新版）
            return jdbc.sql("DELETE FROM calendar_event WHERE owner_id = :owner AND id IN (:ids) AND end_at < :cutoff")
                    .param("owner", owner.value())
                    .param("ids", ids.stream().map(EventId::value).toList())
                    .param("cutoff", cutoff)
                    .update();
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除已歸檔的行程失敗（" + ids.size() + " 筆）", e);
        }
    }

    @Override
    public int restore(OwnerId owner, List<ScheduledEvent> events) {
        try {
            // ON CONFLICT (id) DO NOTHING：已經在行事曆上的那筆不蓋掉、也不算錯（RestoreEventsPort 的約定）。
            // 同一個 id 在別人名下（UUID 撞號，實際上不會發生）一樣跳過，不會變成搶走別人的行程
            Integer restored = transaction.execute(status -> events.stream()
                    .mapToInt(event -> insertSql(owner, event, "ON CONFLICT (id) DO NOTHING").update())
                    .sum());
            return restored == null ? 0 : restored;
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("還原行程失敗（" + events.size() + " 筆）", e);
        }
    }

    private record OwnedRow(OwnerId owner, Row row) {
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
        insertSql(owner, scheduled, "").update();
    }

    // 新增和還原只差在最後那一句（還原遇到已經有的 id 就跳過），欄位的對應只寫一份
    private JdbcClient.StatementSpec insertSql(OwnerId owner, ScheduledEvent scheduled, String onConflict) {
        return jdbc.sql("""
                        INSERT INTO calendar_event (id, owner_id, title, start_at, end_at, category, location, note)
                        VALUES (:id, :owner, :title, :start, :end, :category, :location, :note)
                        """ + onConflict)
                .param("id", scheduled.id().value())
                .param("owner", owner.value())
                .param("title", scheduled.event().title())
                // LocalDateTime 直接交給 PostgreSQL 的 JDBC driver，它對應的正是不帶時區的 TIMESTAMP，不經過任何時區換算
                .param("start", scheduled.event().start())
                .param("end", scheduled.event().end())
                // 分類存 enum 的名稱，跟 V5 的 CHECK 一致；沒有地點、備註就存 NULL（V5 的 CHECK 不收空白字串）
                .param("category", scheduled.event().category().name())
                .param("location", scheduled.event().location().orElse(null))
                .param("note", scheduled.event().note().orElse(null));
    }
}
