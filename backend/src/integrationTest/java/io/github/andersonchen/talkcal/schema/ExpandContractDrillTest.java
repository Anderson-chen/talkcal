package io.github.andersonchen.talkcal.schema;

import static io.github.andersonchen.talkcal.schema.ContactApp.V1;
import static io.github.andersonchen.talkcal.schema.ContactApp.V2;
import static io.github.andersonchen.talkcal.schema.ContactApp.V3;
import static io.github.andersonchen.talkcal.schema.ContactApp.V4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * expand/contract 演練：把 phone 改名成 phone_number，分五次發布，服務一直不停（#18）。
 *
 * 用的是測試專用的表和 migration（src/integrationTest/resources/db/drill），不碰產品的 schema：
 * 要證明的是「做法」行得通，不是為了演練去改業務。換成真的需求時照同樣的發布順序走。
 *
 * 零停機的前提是「每次發布時，同時在線的版本都能共用同一個 schema」：新版上線途中舊版還在跑，出事也要能退回舊版。
 * 所以每次發布在線的是「這次的版本」和「上一版」。正向的測試照順序走完五次；反例各拿掉一個前提，證明那個等待不是多餘的。
 */
@Testcontainers
@DisplayName("#18 expand/contract 演練：欄位改名，服務不停")
class ExpandContractDrillTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    /** 一次發布：schema 跑到哪一支 migration、哪些版本的程式同時在線（新上線的和可能被退回的上一版）。 */
    private record Release(String name, String schema, List<ContactApp> online) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Release R1 = new Release("R1", "1", List.of(V1));
    private static final Release R2 = new Release("R2", "2", List.of(V1, V2));
    private static final Release R3 = new Release("R3", "6", List.of(V2, V3));
    private static final Release R4 = new Release("R4", "6", List.of(V3, V4));
    private static final Release R5 = new Release("R5", "7", List.of(V4));

    private DrillDatabase drill;
    private JdbcClient db;

    @BeforeEach
    void freshDatabase() throws SQLException {
        drill = DrillDatabase.create(POSTGRES);
        db = drill.jdbc();
    }

    @AfterEach
    void dropConnections() throws SQLException {
        drill.close();
    }

    @Test
    @DisplayName("照順序發布 R1～R5：每次同時在線的兩版，讀得到彼此（和更早的發布）寫的、改的每一筆")
    void everyReleaseKeepsBothOnlineVersionsWorking() {
        Map<UUID, String> phones = new LinkedHashMap<>();
        for (Release release : List.of(R1, R2, R3, R4, R5)) {
            drill.migrateTo(release.schema());
            // migration 一跑完先讀：之前的發布寫的、改的，這次在線的兩版都要讀得到（下面的修改會蓋掉值，所以要先讀）
            assertEveryoneReads(release, phones);

            for (ContactApp app : release.online()) {
                UUID id = UUID.randomUUID();
                String phone = release + "-" + app + "-新增";
                app.save(db, id, phone);
                phones.put(id, phone);
            }
            // 在線的版本輪流改既有的每一筆，包含別的版本、更早的發布寫的
            List<UUID> ids = new ArrayList<>(phones.keySet());
            for (int i = 0; i < ids.size(); i++) {
                ContactApp app = release.online().get(i % release.online().size());
                String phone = release + "-" + app + "-修改";
                app.changePhone(db, ids.get(i), phone);
                phones.put(ids.get(i), phone);
            }

            assertEveryoneReads(release, phones);
        }
    }

    private void assertEveryoneReads(Release release, Map<UUID, String> phones) {
        for (ContactApp app : release.online()) {
            phones.forEach((id, phone) -> assertEquals(phone, app.phoneOf(db, id),
                    () -> release + " 時 " + app + " 讀到的電話不對"));
        }
    }

    @Nested
    @DisplayName("反例：沒等到前提就往下一步，在線的某一版就壞")
    class SkippingAStepBreaks {

        @Test
        @DisplayName("expand 跟 backfill 同一次發布（v1 還在線上）：v1 新增聯絡人被 V3 的 CHECK 擋下")
        void backfillWhileV1Online() {
            drill.migrateTo(R3.schema());

            assertThrows(DataIntegrityViolationException.class, () -> V1.save(db, UUID.randomUUID(), "0912"));
        }

        @Test
        @DisplayName("backfill 之前就切讀（R2 的 schema 上跑 v3）：v1 寫的那筆，v3 讀到的是 NULL")
        void readNewColumnBeforeBackfill() {
            drill.migrateTo(R2.schema());
            UUID id = UUID.randomUUID();
            V1.save(db, id, "0912");

            assertNull(V3.phoneOf(db, id));
        }

        @Test
        @DisplayName("backfill 只補 NULL：R2 時 v1 改過的那筆，切讀之後 v3 讀到改之前的電話")
        void backfillOnlyNulls() {
            drill.migrateTo(R2.schema());
            UUID id = UUID.randomUUID();
            V2.save(db, id, "改之前");
            V1.changePhone(db, id, "改之後");

            // V4 換成只補 NULL 的寫法，其餘照舊
            drill.migrateTo("3");
            db.sql("UPDATE drill_contact SET phone_number = phone WHERE phone_number IS NULL").update();

            assertEquals("改之前", V3.phoneOf(db, id));
            assertEquals("改之後", V2.phoneOf(db, id));
        }

        @Test
        @DisplayName("停寫舊欄位時 v2 還在線上：v4 新增的那筆，v2 讀到的是 NULL")
        void stopWritingOldColumnWhileV2Online() {
            drill.migrateTo(R4.schema());
            UUID id = UUID.randomUUID();
            V4.save(db, id, "0912");

            assertNull(V2.phoneOf(db, id));
        }

        @Test
        @DisplayName("contract 時 v3 還在線上：刪掉 phone 之後，v3 新增、修改都失敗")
        void contractWhileV3Online() {
            drill.migrateTo(R4.schema());
            UUID id = UUID.randomUUID();
            V3.save(db, id, "0912");
            drill.migrateTo(R5.schema());

            assertThrows(BadSqlGrammarException.class, () -> V3.save(db, UUID.randomUUID(), "0912"));
            assertThrows(BadSqlGrammarException.class, () -> V3.changePhone(db, id, "0987"));
        }
    }
}
