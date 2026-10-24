package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 行程的歸檔放在 S3 相容的物件儲存（#54）。
 *
 * key 長這樣：calendar-event/owner=&lt;擁有者&gt;/month=&lt;結束的月份&gt;/&lt;存的時間&gt;-&lt;亂數&gt;.jsonl.gz
 * - 擁有者在最前面：一個人的全部歸檔就是一個前綴，還原只列自己的前綴、刪帳號（#52）整個前綴刪掉，
 *   列不到、也刪不到別人的
 * - owner=、month= 的寫法是 Hive 的分區慣例：哪天要用 Athena 直接查歸檔，不必搬資料就能依人、依月份過濾
 * - 檔名帶存的時間：同一個月每天的排程各一包，不會互相蓋掉；照字典順序列出來就是時間順序，
 *   同一筆行程出現好幾次時，後存的那份（比較新）蓋掉先存的
 * - 再加亂數：多台機器同一秒都在跑排程，也不會撞到同一個 key 互相蓋掉
 */
public final class S3EventArchiveAdapter implements EventArchivePort {

    static final String ROOT = "calendar-event/";

    private static final DateTimeFormatter STORED_AT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final ArchiveBucket bucket;
    private final Clock clock;

    public S3EventArchiveAdapter(ArchiveBucket bucket, Clock clock) {
        this.bucket = Objects.requireNonNull(bucket, "bucket 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events) {
        String key = monthPrefix(owner, month)
                + LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).format(STORED_AT)
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl.gz";
        bucket.put(key, JsonLinesGzip.encode(events.stream().map(EventLine::of).toList()));
    }

    @Override
    public List<ScheduledEvent> load(OwnerId owner, YearMonth from, YearMonth to) {
        // 依 id 收：同一筆出現好幾次只留一筆（key 依時間排，後讀到的比較新）
        Map<UUID, ScheduledEvent> events = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            for (String key : bucket.list(monthPrefix(owner, month))) {
                for (EventLine line : JsonLinesGzip.decode(bucket.get(key), EventLine.class)) {
                    events.put(line.id(), line.toScheduledEvent(key));
                }
            }
        }
        return List.copyOf(events.values());
    }

    @Override
    public int deleteAll(OwnerId owner) {
        return bucket.deleteAll(ownerPrefix(owner));
    }

    static String ownerPrefix(OwnerId owner) {
        return ROOT + "owner=" + owner.value() + "/";
    }

    private static String monthPrefix(OwnerId owner, YearMonth month) {
        return ownerPrefix(owner) + "month=" + month + "/";
    }

    /**
     * 歸檔裡一行的形狀：自己的 record，不直接序列化 domain 的型別 —— domain 改了欄位，舊的歸檔照樣讀得回來。
     * v 是格式的版本：哪天欄位的意思變了，讀的時候看 v 決定怎麼翻，不必猜。
     * 擁有者不寫進每一行：它就在 key 裡，一包只有一個人的。
     */
    record EventLine(int v, UUID id, String title, LocalDateTime start, LocalDateTime end,
                     String category, String location, String note) {

        static final int VERSION = 1;

        static EventLine of(ScheduledEvent scheduled) {
            CalendarEvent e = scheduled.event();
            return new EventLine(VERSION, scheduled.id().value(), e.title(), e.start(), e.end(),
                    e.category().name(), e.location().orElse(null), e.note().orElse(null));
        }

        // 讀回來的每一筆重新走一次 domain 的規則，跟從資料庫讀出來一樣：壞掉的歸檔在這裡擋下，不會放回行事曆
        ScheduledEvent toScheduledEvent(String key) {
            if (v != VERSION) {
                throw new IllegalStateException("不認得的歸檔格式版本 " + v + "：" + key);
            }
            try {
                return new ScheduledEvent(new EventId(id), new CalendarEvent(title, start, end,
                        Category.valueOf(category), Optional.ofNullable(location), Optional.ofNullable(note)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalStateException("歸檔裡的行程 " + id + " 不合規則（" + key + "）：" + e.getMessage(), e);
            }
        }
    }
}
