package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 行程的並行修改規則（#50），先焊住，等真的加「修改行程」時才不會漏。
 *
 * 現在行程只能新增和刪除，沒有修改：新增各是各的一列、刪除刪掉就沒了，兩個分頁怎麼搶都不會「悄悄蓋掉對方」。
 * 這次不加修改 API：產品上改行程是「刪掉、請助理重新提議」，還沒有編輯畫面；只為了示範樂觀鎖做一個沒有畫面在用的 API，
 * 多出來的是要維護的程式，不是功能。
 *
 * 但修改一加進來，遺失更新就來了：A、B 兩個分頁都打開同一筆行程，A 改時間、B 改地點，B 晚存，A 改的時間就被蓋回去，
 * 而且兩邊都看到「儲存成功」。到那時候的規則（樂觀鎖）：
 * <ol>
 *   <li>新的 migration 給 calendar_event 加 {@code version BIGINT NOT NULL DEFAULT 0}</li>
 *   <li>讀行程時把 version 一起回給前端；修改的請求帶著它讀到的 version</li>
 *   <li>SQL 是 {@code UPDATE calendar_event SET ..., version = version + 1 WHERE id = :id AND owner_id = :owner AND version = :version}：
 *       更新了 0 列 = 這段期間有人改過（或是別人的、已經刪掉）</li>
 *   <li>0 列就回 409 Conflict，前端顯示「這筆行程剛被改過了，請重新整理後再改」，不自動重試 ——
 *       重試等於拿舊的畫面再蓋一次，正好就是要擋的事</li>
 *   <li>owner_id 條件照樣在（#104）：版本對了也改不到別人的</li>
 * </ol>
 *
 * 為什麼是樂觀鎖、不是 SELECT ... FOR UPDATE：衝突的窗口是「使用者看著畫面想」的那幾分鐘，橫跨好幾個 HTTP 請求，
 * 資料庫的鎖不可能抓那麼久；行程又很少兩邊同時改，衝突時才讓一方重來，平常不必等。
 *
 * 這個測試守第 3 點：正式程式裡每一句 {@code UPDATE calendar_event} 都要比對 version、也要把 version 加一。
 * 沒有這句 SQL 的時候（現在）它不擋任何東西；有人加了修改卻沒帶 version，它就紅。
 * 掃的是原始碼文字而不是 ArchUnit：ArchUnit 看得到類別之間的依賴，看不到字串裡的 SQL。
 */
@DisplayName("並行修改的規則：UPDATE calendar_event 一定要帶 version（樂觀鎖）")
class ConcurrentEditRuleTest {

    // 一句 UPDATE calendar_event：到 text block 結束（"""）或一般字串結束（"）為止
    private static final Pattern UPDATE_EVENT = Pattern.compile(
            "UPDATE\\s+calendar_event\\b.*?(?=\"\"\"|\")", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHECKS_VERSION = Pattern.compile("\\bversion\\s*=\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUMPS_VERSION = Pattern.compile(
            "\\bversion\\s*=\\s*version\\s*\\+\\s*1", Pattern.CASE_INSENSITIVE);

    /** 原始碼裡沒帶 version 的 UPDATE calendar_event，一句一筆。 */
    static List<String> updatesWithoutVersion(String source) {
        Matcher m = UPDATE_EVENT.matcher(source);
        return m.results()
                .map(r -> r.group())
                .filter(sql -> !CHECKS_VERSION.matcher(sql).find() || !BUMPS_VERSION.matcher(sql).find())
                .map(sql -> sql.strip().replaceAll("\\s+", " "))
                .toList();
    }

    @Test
    @DisplayName("正式程式裡每一句 UPDATE calendar_event 都比對 version 並加一")
    void everyEventUpdateUsesOptimisticLock() throws IOException {
        Path main = Path.of("src/main/java");
        // 路徑錯了就什麼都掃不到、永遠綠：先確定真的掃到程式碼
        assertTrue(Files.isDirectory(main.resolve("io/github/andersonchen/talkcal/calendar")), "找不到正式程式：" + main.toAbsolutePath());
        try (Stream<Path> files = Files.walk(main)) {
            List<String> violations = files.filter(f -> f.toString().endsWith(".java"))
                    .flatMap(f -> updatesWithoutVersion(read(f)).stream().map(sql -> main.relativize(f) + "：" + sql))
                    .toList();
            assertEquals(List.of(), violations,
                    "修改行程要用樂觀鎖：WHERE 帶 version = :version、SET 帶 version = version + 1，更新 0 列回 409（規則寫在 ConcurrentEditRuleTest）");
        }
    }

    @Test
    @DisplayName("反例：沒比對 version、或比對了卻沒加一，都抓得到")
    void catchesUpdatesWithoutVersion() {
        // 最常見的寫法：誰最後存誰贏
        assertEquals(1, updatesWithoutVersion("""
                jdbc.sql(\"""
                        UPDATE calendar_event SET title = :title WHERE id = :id AND owner_id = :owner
                        \""")
                """).size());
        // 比對了但沒加一：第二個人拿同一個 version 照樣改得過
        assertEquals(1, updatesWithoutVersion("""
                jdbc.sql("UPDATE calendar_event SET title = :title WHERE id = :id AND version = :version")
                """).size());
        // 大小寫不同也一樣
        assertEquals(1, updatesWithoutVersion("""
                jdbc.sql("update Calendar_Event set title = :title where id = :id")
                """).size());
    }

    @Test
    @DisplayName("正確的寫法、以及別的表的 UPDATE，不會被誤擋")
    void acceptsOptimisticLock() {
        assertEquals(List.of(), updatesWithoutVersion("""
                jdbc.sql(\"""
                        UPDATE calendar_event
                        SET title = :title, version = version + 1
                        WHERE id = :id AND owner_id = :owner AND version = :version
                        \""")
                """));
        assertEquals(List.of(), updatesWithoutVersion("""
                jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() WHERE conversation_id = :id")
                """));
        assertEquals(List.of(), updatesWithoutVersion("""
                jdbc.sql("UPDATE calendar_event_archive SET note = :note WHERE id = :id")
                """));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
