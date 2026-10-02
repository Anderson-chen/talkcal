package eat.conversation.adapter.out.persistence.postgres;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.ConversationChangedException;
import eat.conversation.application.domain.model.ConversationId;
import eat.conversation.application.port.out.LoadConversationPort;
import eat.conversation.application.port.out.SaveConversationPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 Conversation 存進 PostgreSQL、讀出來（表的定義在 db/migration/V1__create_conversations.sql）。
 *
 * 一個 adapter 實作讀、存兩個 port：它們操作的是同一個 aggregate、同兩張表，
 * 拆成兩個類別只會讓「這兩張表長什麼樣」的知識散在兩處。
 *
 * 交易用 TransactionTemplate（程式化）而不是 @Transactional（宣告式）：
 * - 這個類別是 final，@Transactional 要靠 Spring 產生子類別當代理，final 類別代理不了
 * - 交易範圍在程式碼裡看得到，也沒有「同一個類別裡自己呼叫自己，交易悄悄不生效」那個坑
 * core 完全不知道有交易：SaveConversationPort 只說「整段對話一起存」，怎麼做到是這裡的事。
 */
public final class JdbcConversationAdapter implements LoadConversationPort, SaveConversationPort {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcConversationAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public Optional<Conversation> load(ConversationId id) {
        try {
            // 對話和訊息用一句 SQL（LEFT JOIN）一起讀：同一句 SQL 看到的是同一個時間點的資料。
            // 分兩句讀的話，中間可能剛好有別的請求存檔，讀到「舊版號配上新訊息」。
            List<Row> rows = jdbc.sql("""
                            SELECT c.instruction, c.version, m.role, m.text
                            FROM conversation c
                            LEFT JOIN conversation_message m ON m.conversation_id = c.id
                            WHERE c.id = :id
                            ORDER BY m.position
                            """)
                    .param("id", id.value())
                    .query(JdbcConversationAdapter::row)
                    .list();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            List<Conversation.Message> messages = new ArrayList<>();
            for (Row row : rows) {
                // 還沒有任何訊息的對話，LEFT JOIN 會回一列訊息欄位全是 NULL 的
                if (row.role() != null) {
                    messages.add(new Conversation.Message(Conversation.Role.valueOf(row.role()), row.text()));
                }
            }
            Row first = rows.getFirst();
            // restore 會把每則訊息重新走一遍規則（交替、不可空白），資料庫裡的壞資料在這裡就被擋下
            return Optional.of(Conversation.restore(id, first.instruction(), messages, first.version()));
        } catch (DataAccessException e) {
            // port 的約定：讀取本身失敗丟非受檢例外。翻成 IllegalStateException，跟模型、檢索出事同一類（502）
            throw new IllegalStateException("讀取對話失敗：" + id, e);
        }
    }

    @Override
    public void save(Conversation conversation) {
        try {
            transaction.executeWithoutResult(status -> {
                claimVersion(conversation);
                appendNewMessages(conversation);
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存對話失敗：" + conversation.id(), e);
        }
        // ConversationChangedException 不是 DataAccessException，不會被上面攔下：
        // 它從交易裡丟出來時，TransactionTemplate 先 rollback 再原樣往外丟，什麼都沒寫進去
    }

    /**
     * 樂觀鎖：用版本號確認「從讀出來到現在，沒有別的請求先存過」，同時把版本號往前推一格。
     *
     * 新對話（version 0）是插入；插不進去代表同一個 ID 已經被存過了。
     * 舊對話是「WHERE version = 讀到的那版」的 UPDATE；更新到 0 列代表版本已經被別人推進了。
     * 這句 UPDATE 同時鎖住那一列，交易結束前別的請求動不了這段對話，下面算「已經存了幾則」才算得準。
     */
    private void claimVersion(Conversation conversation) {
        int affected;
        if (conversation.version() == 0) {
            affected = jdbc.sql("""
                            INSERT INTO conversation (id, instruction, version)
                            VALUES (:id, :instruction, 1)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", conversation.id().value())
                    .param("instruction", conversation.instruction().orElse(null))
                    .update();
        } else {
            affected = jdbc.sql("""
                            UPDATE conversation
                            SET version = version + 1, updated_at = now()
                            WHERE id = :id AND version = :version
                            """)
                    .param("id", conversation.id().value())
                    .param("version", conversation.version())
                    .update();
        }
        if (affected == 0) {
            throw new ConversationChangedException(conversation.id());
        }
    }

    /**
     * 歷史只能追加（規則 3），所以只要插入「比資料庫裡多出來的那幾則」，已經存過的不必動。
     */
    private void appendNewMessages(Conversation conversation) {
        int stored = jdbc.sql("SELECT count(*) FROM conversation_message WHERE conversation_id = :id")
                .param("id", conversation.id().value())
                .query(Integer.class)
                .single();
        List<Conversation.Message> messages = conversation.messages();
        if (messages.size() < stored) {
            // 版本號對得上，訊息卻比資料庫少：不可能發生，除非歷史被改過（違反規則 3）。寧可炸掉也不要亂寫
            throw new IllegalStateException("對話 " + conversation.id() + " 的訊息比資料庫裡少（"
                    + messages.size() + " < " + stored + "），歷史不該變短");
        }
        for (int position = stored; position < messages.size(); position++) {
            Conversation.Message message = messages.get(position);
            jdbc.sql("""
                            INSERT INTO conversation_message (conversation_id, position, role, text)
                            VALUES (:id, :position, :role, :text)
                            """)
                    .param("id", conversation.id().value())
                    .param("position", position)
                    .param("role", message.role().name())
                    .param("text", message.text())
                    .update();
        }
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("instruction"), rs.getLong("version"), rs.getString("role"), rs.getString("text"));
    }

    private record Row(String instruction, long version, String role, String text) {
    }
}
