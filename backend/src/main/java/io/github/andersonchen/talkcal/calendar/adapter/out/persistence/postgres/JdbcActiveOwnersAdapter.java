package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * 從兩張表數出最近有動作的擁有者（#36 的活躍使用者）：calendar_event（存過行程）和 calendar_assistant_message（跟助理說過話）。
 *
 * 時間看兩張表的 created_at：
 * - calendar_event：寫進來的時間（V2）
 * - calendar_assistant_message：存檔是整段刪掉重插（V7），所以 created_at 其實是「這段對話最後一次說話」—— 正好是要的
 *
 * 沒有 created_at 的索引：每次是兩張表全掃。目前的資料量不痛；
 * 被抓的頻率是 Alloy 的 15 秒一次乘上幾個期間，資料多了先看這句 SQL 的時間再決定加索引還是快取。
 */
public final class JdbcActiveOwnersAdapter implements CountActiveOwnersPort {

    private final JdbcClient jdbc;

    public JdbcActiveOwnersAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public int countActiveOwners(Instant since) {
        Objects.requireNonNull(since, "since 不可為 null");
        try {
            // UNION（不是 UNION ALL）先去掉兩邊重複的人，外面的 count 只是數列數
            return jdbc.sql("""
                            SELECT count(*) FROM (
                                SELECT owner_id FROM calendar_event WHERE created_at >= :since
                                UNION
                                SELECT owner_id FROM calendar_assistant_message WHERE created_at >= :since
                            ) active
                            """)
                    .param("since", Timestamp.from(since))
                    .query(Integer.class)
                    .single();
        } catch (DataAccessException e) {
            throw new IllegalStateException("數活躍使用者失敗：since=" + since, e);
        }
    }
}
