package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.port.out.GuestDemoUsagePort;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalInt;

/** 表的定義和為什麼放資料庫，見 V15__create_guest_demo_usage.sql */
public final class JdbcGuestDemoUsageAdapter implements GuestDemoUsagePort {

    private final JdbcClient jdbc;

    public JdbcGuestDemoUsageAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public OptionalInt use(String client, LocalDate day, int limit) {
        Objects.requireNonNull(client, "client 不可為 null");
        Objects.requireNonNull(day, "day 不可為 null");
        if (limit < 1) {
            throw new IllegalArgumentException("上限要大於 0：" + limit);
        }
        // 已經到上限時 DO UPDATE 的 WHERE 不成立，這列不更新，RETURNING 也不回任何列。
        // 檢查和加一在同一句，同時送來的請求由資料庫的列鎖排隊，不會一起加過頭
        return jdbc.sql("""
                        INSERT INTO guest_demo_usage (client, day, used) VALUES (:client, :day, 1)
                        ON CONFLICT (client, day) DO UPDATE SET used = guest_demo_usage.used + 1
                        WHERE guest_demo_usage.used < :limit
                        RETURNING used
                        """)
                .param("client", client)
                .param("day", day)
                .param("limit", limit)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    /** 不是 port：這張表自己的維護工作，由每天的清理排程直接呼叫 */
    public int deleteBefore(LocalDate day) {
        Objects.requireNonNull(day, "day 不可為 null");
        return jdbc.sql("DELETE FROM guest_demo_usage WHERE day < :day").param("day", day).update();
    }
}
