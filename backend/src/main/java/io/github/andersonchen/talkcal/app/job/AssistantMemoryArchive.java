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
 * 每天把 AI 助理的舊對話搬到歸檔（#54）：超過保留期限沒再說過話的，整段壓縮放進物件儲存，存好了才從資料庫刪掉。
 * 也負責反過來：從歸檔把某個人某幾個月的對話放回資料庫。
 *
 * 為什麼要搬走：每段對話都存著工具呼叫和工具結果（一輪好幾列），前端只記最新那一段的 id，
 * 按「清空」（前端只丟掉 id，後端不刪）或換瀏覽器之後，舊的那段就沒人接得回來了，只會一直留在表裡。
 * 以前是直接刪掉；現在先歸檔：線上的表一樣保持小，真的要找（查 AI 當時說了什麼、使用者要匯出資料）還找得回來。
 * 這裡是唯一會刪對話紀錄的地方。
 *
 * 順序是重點：先上傳 → 上傳成功 → 才刪。上傳失敗的那段一列都不刪，留在資料庫等明天再試。
 *
 * 助理發出的提議卡片（#223，只用在產品指標）也在這裡清，保留期限跟對話一樣，但直接刪、不歸檔：
 * 它是統計用的紀錄，不是使用者的資料；超過期限的卡片，對話本身都搬走了，使用者也不會再回頭按它。
 *
 * 為什麼放在 app.job、不放在 calendar 的 adapter.in：計時器也算是「從外面推進來」的入口，
 * 但它要直接用 JdbcAssistantMemoryRepository（不是 port，是這張表自己的維護工作）和歸檔的 bucket，
 * 而 ArchitectureTest 只准組裝根裡的 app.config、app.job 認識 adapter.out。
 * 對話記憶本來就不在 core 裡（core 不知道有「對話」這回事，見 JdbcAssistantMemoryRepository），所以歸檔也不走 core 的 port。
 *
 * 類別和建構子是 public：由 app.config 的 CalendarConfiguration 建出來，跨 package 了。
 * C 重構把助理搬成自己的模組時，這裡跟著改成呼叫那邊的 use case。
 */
public final class AssistantMemoryArchive {

    private static final Logger log = LoggerFactory.getLogger(AssistantMemoryArchive.class);

    static final String ROOT = "assistant-conversation/";

    // 一次讀幾段對話進記憶體。一段通常幾十列，100 段也才幾千列
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

    // 每天凌晨四點（台北時間）：沒人在用的時候。多台一起跑也沒關係：同一段對話的 key 是固定的，重傳只是蓋成同樣的內容
    @Scheduled(cron = "0 0 4 * * *", zone = "${calendar.zone}")
    void run() {
        Instant cutoff = clock.instant().minus(retention);
        Report report = archiveInactiveSince(cutoff);
        // 卡片跟歸檔無關，上傳失敗也照清：它不是使用者的資料，留著只是佔表
        int deletedProposals = proposals.deleteCreatedBefore(cutoff);
        log.info("清掉舊提議卡片 {} 張", deletedProposals);
        if (report.failed() > 0) {
            log.warn("AI 助理的舊對話有 {} 段沒存進歸檔，留在資料庫明天再試；已歸檔 {} 段", report.failed(), report.archived());
        } else {
            log.info("AI 助理的舊對話歸檔了 {} 段（超過 {} 天沒說話）", report.archived(), retention.toDays());
        }
    }

    /** 搬走 cutoff 之前就沒再說過話的對話。排程和測試都從這裡進來。 */
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
            // 不滿一批 = 舊的讀完了；有失敗、或一段都沒刪到 = 再讀一次也是同一批，停下來免得繞不完。
            // 跟行程的 ArchiveEventsService 同一組停止條件，改一邊要對照另一邊
            if (batch.size() < BATCH || failed > 0 || deletedThisBatch == 0) {
                return new Report(archived, failed);
            }
        }
    }

    /**
     * 把 owner 最後說話在 months 這幾個月（兩端都含）的對話放回資料庫，回傳放回幾段。
     * 已經在資料庫裡的同一段跳過（不拿舊的蓋掉新的）。放回去的對話時間還是舊的，下次排程會再歸檔一次。
     * 範圍合不合規由 MonthRange 建構時檢查。
     */
    public int restore(OwnerId owner, MonthRange months) {
        int restored = 0;
        for (String key : bucket.keys(ROOT, owner, months)) {
            StoredConversation conversation =
                    MessageLine.toConversation(owner, JsonLinesGzip.decode(bucket.get(key), MessageLine.class), key);
            if (memory.restore(conversation)) {
                restored++;
            }
        }
        return restored;
    }

    /** 刪掉 owner 在歸檔裡的全部對話，回傳刪了幾包。給刪除帳號（#52）用，歸檔不能變成刪不掉的備份。 */
    public int deleteAll(OwnerId owner) {
        return bucket.deleteAll(ArchiveBucket.ownerPrefix(ROOT, owner));
    }

    /**
     * key：assistant-conversation/owner=&lt;擁有者&gt;/month=&lt;最後說話的月份&gt;/&lt;conversation_id&gt;.jsonl.gz
     * 一段對話一包、key 固定：重傳只會蓋成同樣的內容，不會多出重複的。月份用 calendar.zone 的時區算（使用者看到的那個月）。
     * 前面的排法跟行程（S3EventArchiveAdapter）共用，在 ArchiveBucket。
     */
    private String key(StoredConversation conversation) {
        YearMonth month = YearMonth.from(conversation.lastActive().atZone(clock.getZone()));
        return ArchiveBucket.monthPrefix(ROOT, conversation.owner(), month) + conversation.conversationId() + ".jsonl.gz";
    }

    /**
     * @param archived 存進歸檔、也從資料庫刪掉的段數
     * @param failed   存不進去的段數（還在資料庫裡）
     */
    public record Report(int archived, int failed) {
    }

    /**
     * 歸檔裡一行 = 一則訊息，欄位跟資料表一樣（工具呼叫是 JSON 原文）。v 是格式版本。
     * 擁有者不寫進每一行：它就在 key 裡，還原時以 key 的擁有者為準 —— 一包不可能把訊息放回別人名下。
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

        // 一包就是一段對話（key 是 conversation_id），所以直接組回一段，不必再分組
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
