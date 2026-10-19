package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.out.EraseOwnerDataPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 刪掉一個擁有者在 calendar 裡的所有資料（#52）：行程（V2、V11）和 AI 助理的對話記憶（V7、V11）。
 *
 * 為什麼另開一個類別，不加在 JdbcCalendarEventAdapter、JdbcAssistantMemoryRepository 裡：
 * 「這個人的資料散在哪幾張表」是一份清單，放在一個地方才看得出有沒有漏。之後新增帶 owner_id 的表
 * （例如冪等 key、歸檔），加在 TABLES 這一行；漏了 PrivacySystemTest 會抓到。
 *
 * 一個交易刪完：刪到一半斷線，不會留下「行程刪了、對話還在」的狀態。
 */
public final class JdbcOwnerDataAdapter implements EraseOwnerDataPort {

    // 表名寫死在這裡，不是外面傳進來的：拼進 SQL 的字串只有這幾個常數
    static final List<String> TABLES = List.of("calendar_event", "calendar_assistant_message");

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcOwnerDataAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public int eraseAll(OwnerId owner) {
        Objects.requireNonNull(owner, "owner 不可為 null");
        try {
            Integer erased = transaction.execute(status -> TABLES.stream()
                    .mapToInt(table -> jdbc.sql("DELETE FROM " + table + " WHERE owner_id = :owner")
                            .param("owner", owner.value())
                            .update())
                    .sum());
            return erased == null ? 0 : erased;
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("刪除擁有者的資料失敗：" + owner, e);
        }
    }
}
