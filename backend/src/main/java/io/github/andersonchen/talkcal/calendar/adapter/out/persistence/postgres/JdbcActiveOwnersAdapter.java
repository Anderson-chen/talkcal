package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 從兩張表數出最近有動作的擁有者（#36 的活躍使用者）：calendar_event（存過行程）和 calendar_assistant_message（跟助理說過話）。
 *
 * 時間看兩張表的 created_at：
 * - calendar_event：寫進來的時間（V2）
 * - calendar_assistant_message：存檔是整段刪掉重插（V7），所以 created_at 其實是「這段對話最後一次說話」—— 正好是要的
 *
 * 幾個期間在同一句 SQL 裡用 FILTER 一起數：兩張表各掃一次，不是每個期間各掃一次。
 * 只掃最早那個時間點之後的資料（WHERE created_at >= 最早的 since），其他期間再從裡面篩。
 * 沒有 created_at 的索引：目前的資料量不痛；呼叫端（ActiveUsersMetrics）也會快取，資料多了先看這句的時間再決定加索引。
 */
public final class JdbcActiveOwnersAdapter implements CountActiveOwnersPort {

    private final JdbcClient jdbc;

    public JdbcActiveOwnersAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public List<Integer> countActiveOwners(List<Instant> sinces) {
        Objects.requireNonNull(sinces, "sinces 不可為 null");
        if (sinces.isEmpty()) {
            return List.of();
        }
        // 欄位數跟著 sinces 走，但每個值都是參數（:s0、:s1…），沒有任何外來的字串拼進 SQL
        String counts = IntStream.range(0, sinces.size())
                .mapToObj(i -> "count(DISTINCT owner_id) FILTER (WHERE created_at >= :s" + i + ")")
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT %s FROM (
                    SELECT owner_id, created_at FROM calendar_event WHERE created_at >= :earliest
                    UNION ALL
                    SELECT owner_id, created_at FROM calendar_assistant_message WHERE created_at >= :earliest
                ) activity
                """.formatted(counts))
                .param("earliest", utc(sinces.stream().min(Instant::compareTo).orElseThrow()));
        for (int i = 0; i < sinces.size(); i++) {
            statement = statement.param("s" + i, utc(Objects.requireNonNull(sinces.get(i), "since 不可為 null")));
        }
        try {
            return statement.query((ResultSet rs, int row) -> columns(rs, sinces.size())).single();
        } catch (DataAccessException e) {
            throw new IllegalStateException("數活躍使用者失敗：sinces=" + sinces, e);
        }
    }

    // 帶著時區送：欄位是 TIMESTAMPTZ，用 java.sql.Timestamp 的話換算要看 JVM 的時區，容器（UTC）和本機（台北）會差 8 小時
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<Integer> columns(ResultSet rs, int count) throws SQLException {
        List<Integer> values = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            values.add(rs.getInt(i));
        }
        return List.copyOf(values);
    }
}
