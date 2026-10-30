package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
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
 * （前面的排法和理由在 ArchiveBucket，跟對話共用）
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
        String key = ArchiveBucket.monthPrefix(ROOT, owner, month)
                + LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).format(STORED_AT)
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl.gz";
        bucket.put(key, JsonLinesGzip.encode(events.stream().map(EventLine::of).toList()));
    }

    @Override
    public List<ScheduledEvent> load(OwnerId owner, MonthRange months) {
        // 依 id 收：同一筆出現好幾次只留一筆（同一個月的 key 依存的時間排，後讀到的比較新）
        Map<UUID, ScheduledEvent> events = new LinkedHashMap<>();
        for (String key : bucket.keys(ROOT, owner, months)) {
            for (EventLine line : JsonLinesGzip.decode(bucket.get(key), EventLine.class)) {
                events.put(line.id(), line.toScheduledEvent(key));
            }
        }
        return List.copyOf(events.values());
    }

    @Override
    public int deleteAll(OwnerId owner) {
        return bucket.deleteAll(ArchiveBucket.ownerPrefix(ROOT, owner));
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
