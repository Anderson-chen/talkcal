package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.JsonLinesGzip;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository.StoredConversation;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository.StoredMessage;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantProposalAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 每天把超過保留期限沒再說話的 AI 助理對話整段壓縮、放進物件儲存，存好了才從資料庫刪；也能把某人某幾個月的對話放回來。
 *
 * 對話要搬走：每段都存著工具呼叫和結果，前端只記最新那一段的 id，按「清空」（只丟掉 id）或換瀏覽器之後，
 * 舊的那段就沒有入口，只會一直留在表裡。不直接刪：要查 AI 當時說了什麼、使用者要匯出資料時還找得回來。
 * 上傳失敗的那段一列都不刪，留到明天再試。
 *
 * 助理的提議卡片也在這裡清，保留期限跟對話一樣，但直接刪、不歸檔：卡片只是產品指標的紀錄，不是使用者的資料。
 *
 * 放在 app.job：這個類別直接用 JdbcAssistantMemoryRepository 和歸檔的 bucket，ArchitectureTest 只准 app.config、app.job import adapter.out。
 * 對話不在 core 裡（見 JdbcAssistantMemoryRepository），所以不走 core 的 port。
 */
public final class AssistantMemoryArchive {

    private static final Logger log = LoggerFactory.getLogger(AssistantMemoryArchive.class);

    static final String ROOT = "assistant-conversation/";

    // 一段通常幾十列，100 段也才幾千列
    private static final int BATCH = 100;

    private final JdbcAssistantMemoryRepository memory;
    private final JdbcAssistantProposalAdapter proposals;
    private final ArchiveBucket bucket;
    private final Duration retention;
    private final Clock clock;

    public AssistantMemoryArchive(JdbcAssistantMemoryRepository memory, JdbcAssistantProposalAdapter proposals,
                                  ArchiveBucket bucket, Duration retention, Clock clock) {
        this.memory = Objects.requireNonNull(memory, "memory 不可為 null");
        this.proposals = Objects.requireNonNull(proposals, "proposals 不可為 null");
        this.bucket = Objects.requireNonNull(bucket, "bucket 不可為 null");
        this.retention = Objects.requireNonNull(retention, "retention 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        if (retention.isNegative() || retention.isZero()) {
            // 0 會把正在聊的那段也搬走
            throw new IllegalArgumentException("保留期限要大於 0：" + retention);
        }
    }

    // 凌晨四點沒人在用。多台同時執行也沒關係：同一段對話的 key 固定，重傳只是蓋成同樣的內容
    @Scheduled(cron = "0 0 4 * * *", zone = "${calendar.zone}")
    void run() {
        Instant cutoff = clock.instant().minus(retention);
        Report report = archiveInactiveSince(cutoff);
        // 卡片跟歸檔無關，上傳失敗也照清
        int deletedProposals = proposals.deleteCreatedBefore(cutoff);
        log.info("清掉舊提議卡片 {} 張", deletedProposals);
        if (report.failed() > 0) {
            log.warn("AI 助理的舊對話有 {} 段沒存進歸檔，留在資料庫明天再試；已歸檔 {} 段", report.failed(), report.archived());
        } else {
            log.info("AI 助理的舊對話歸檔了 {} 段（超過 {} 天沒說話）", report.archived(), retention.toDays());
        }
    }

    public Report archiveInactiveSince(Instant cutoff) {
        int archived = 0;
        int failed = 0;
        while (true) {
            List<StoredConversation> batch = memory.findInactiveSince(cutoff, BATCH);
            int deletedThisBatch = 0;
            for (StoredConversation conversation : batch) {
                try {
                    bucket.put(key(conversation), JsonLinesGzip.encode(MessageLine.of(conversation)));
                } catch (IllegalStateException e) {
                    log.warn("對話 {} 存不進歸檔，這段先不刪", conversation.conversationId(), e);
                    failed++;
                    continue;
                }
                if (memory.deleteIfInactiveSince(conversation.owner(), conversation.conversationId(), cutoff)) {
                    deletedThisBatch++;
                }
            }
            archived += deletedThisBatch;
            // 不滿一批 = 舊的讀完了；有失敗、或一段都沒刪到 = 再讀一次也是同一批，停止迴圈，避免無限重複。
            // ArchiveEventsService 是同一組停止條件，改一邊要對照另一邊
            if (batch.size() < BATCH || failed > 0 || deletedThisBatch == 0) {
                return new Report(archived, failed);
            }
        }
    }

    /**
     * 把 owner 最後說話在 months 這幾個月（兩端都含）的對話放回資料庫，回傳放回幾段。
     * 資料庫裡已經有的同一段跳過，不拿舊的蓋掉新的。放回去的對話時間還是舊的，下次排程會再歸檔。
     */
    public int restore(OwnerId owner, MonthRange months) {
        int restored = 0;
        for (String key : bucket.keys(ROOT, owner, months.from(), months.to())) {
            StoredConversation conversation =
                    MessageLine.toConversation(owner, JsonLinesGzip.decode(bucket.get(key), MessageLine.class), key);
            if (memory.restore(conversation)) {
                restored++;
            }
        }
        return restored;
    }

    /** 回傳刪了幾包。刪帳號時歸檔也要跟著刪，不能變成刪不掉的備份。 */
    public int deleteAll(OwnerId owner) {
        return bucket.deleteAll(ArchiveBucket.ownerPrefix(ROOT, owner));
    }

    /**
     * from 的全部對話歸檔改到 to 名下，之後 restore(to, …) 放得回來。擁有者只記在 key 裡（見 MessageLine），搬 key 就夠了。
     * 中途失敗丟 IllegalStateException，再呼叫一次會把剩下的搬完
     */
    public void transfer(OwnerId from, OwnerId to) {
        bucket.moveAll(ArchiveBucket.ownerPrefix(ROOT, from), ArchiveBucket.ownerPrefix(ROOT, to));
    }

    /**
     * assistant-conversation/owner=&lt;擁有者&gt;/month=&lt;最後說話的月份&gt;/&lt;conversation_id&gt;.jsonl.gz
     * 一段對話一包、key 固定：重傳只會蓋成同樣的內容，不會多出重複的。key 前綴的排法見 ArchiveBucket。
     */
    private String key(StoredConversation conversation) {
        YearMonth month = YearMonth.from(conversation.lastActive().atZone(clock.getZone()));
        return ArchiveBucket.monthPrefix(ROOT, conversation.owner(), month) + conversation.conversationId() + ".jsonl.gz";
    }

    /**
     * 這一輪搬了幾段、幾段沒搬成。
     *
     * @param archived 存進歸檔、也從資料庫刪掉的段數
     * @param failed   存不進去、還在資料庫裡的段數
     */
    public record Report(int archived, int failed) {
    }

    /**
     * 歸檔裡的一行是一則訊息，欄位跟資料表一樣（工具呼叫是 JSON 原文）。v 是格式版本。
     * 擁有者不寫進每一行：還原時以 key 裡的擁有者為準，一包不可能把訊息放回別人名下。
     */
    record MessageLine(int v, UUID conversationId, Instant lastActive, int position, String type, String content,
                       String toolCalls, String toolResponses, Instant createdAt) {

        static final int VERSION = 1;

        static List<MessageLine> of(StoredConversation c) {
            return c.messages().stream()
                    .map(m -> new MessageLine(VERSION, c.conversationId(), c.lastActive(), m.position(), m.type(),
                            m.content(), m.toolCalls(), m.toolResponses(), m.createdAt()))
                    .toList();
        }

        // 一包就是一段對話，不必再分組
        static StoredConversation toConversation(OwnerId owner, List<MessageLine> lines, String key) {
            if (lines.isEmpty()) {
                throw new IllegalStateException("歸檔是空的：" + key);
            }
            for (MessageLine line : lines) {
                if (line.v() != VERSION) {
                    throw new IllegalStateException("不認得的歸檔格式版本 " + line.v() + "：" + key);
                }
            }
            MessageLine first = lines.getFirst();
            return new StoredConversation(owner, first.conversationId(), first.lastActive(),
                    lines.stream().map(l -> new StoredMessage(l.position(), l.type(), l.content(),
                            l.toolCalls(), l.toolResponses(), l.createdAt())).toList());
        }
    }
}
