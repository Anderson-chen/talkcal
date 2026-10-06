package eat.conversation.adapter.out.knowledge;

import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知識庫：讀 classpath 裡 knowledge/ 底下的 markdown，照 ## 標題切成片段。
 *
 * 切段（chunking）是 RAG 裡「一份文件要切成幾塊、每塊各算一個向量」那一步。
 * 不整份算一個向量，是因為一份「鮭魚.md」同時講營養、保存、烹調，
 * 整份的向量方向會落在三者中間，哪一種問題都對不準；切太細又會掉上下文 ——
 * 單看「中火煎四分鐘再翻面」，不知道在煎什麼。
 * 照 ## 切剛好落在中間：每一段講一件事，而且標題本身就是這一段的主題。
 * 掉的上下文由出處補回來：「鮭魚.md > 烹調建議」會跟內文一起算進向量。
 *
 * 內容全是中文，因為目前的 embedding 模型（bge-m3）是挑中文檢索能力選的。
 */
public final class MarkdownKnowledgeBase {

    /** metadata 裡放出處的 key。 */
    public static final String SOURCE = "source";

    private static final String DIRECTORY = "knowledge/";

    // 明列檔名，而不是去掃 knowledge/ 目錄：classpath 上的「目錄」在 IDE 裡是資料夾、
    // 打包之後是 jar 裡的一串 entry，兩種要用不同的方式列舉。三個檔案用不著那套機制 ——
    // 代價是新增檔案時要來這裡補一行，漏了就是那份文件檢索不到。
    // 順序就是 Document 的順序
    private static final List<String> FILES = List.of("鮭魚.md", "雞胸肉.md", "酪梨.md");

    private MarkdownKnowledgeBase() {
    }

    /**
     * 讀出所有檔案、切好段。
     *
     * 讀不到就直接丟例外，不回空清單：這些檔案是跟著程式一起打包的，
     * 少了一份是建置出錯，應該讓應用啟動就失敗，而不是安靜地少一塊知識。
     * （跟 embedding server 沒開的處理剛好相反 —— 那是外部環境，晚一點可能就好了；這個不會。）
     */
    public static List<Document> documents() {
        return FILES.stream()
                .flatMap(fileName -> chunk(fileName, read(fileName)).stream())
                .toList();
    }

    /**
     * 照 ## 標題切段。出處是「檔名 > 標題」，內文是標題底下到下一個 ## 之前的文字。
     *
     * 只切第二層：# 是整份文件的標題，檔名已經說了是哪份文件，不算進任何一段；
     * ### 以下留在所屬那一段的內文裡，不再往下切 —— 切到那麼細，一段會短到撐不起意思。
     * 第一個 ## 之前如果有內文，它屬於整份文件，出處就只有檔名，不能丟掉。
     * 有標題沒內文的段落略過：這種通常是還沒寫的段落，算出來的向量只有標題的意思。
     *
     * Document 的文字是「出處 + 換行 + 內文」：向量就是對這段文字算的，標題本身帶著語意
     * （「鮭魚.md > 營養成分」裡的「營養」）。跟拿掉 domain 之前的算法一字不差，
     * RetrievalQualityTest 量出來的門檻才能沿用。出處另外放一份在 metadata，要單獨拿時不必去切字串。
     */
    static List<Document> chunk(String fileName, String markdown) {
        List<Document> documents = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        // lines() 認得 \n、\r\n、\r 三種換行。這很重要：這台機器的 git 會在 checkout 時
        // 把換行轉成 CRLF，若自己用 split("\n") 切，每一行尾巴都會黏著一個看不見的 \r，
        // 出處會變成「鮭魚.md > 營養成分\r」
        for (String line : markdown.lines().toList()) {
            if (line.startsWith("## ")) {
                addSection(documents, fileName, heading, body);
                heading = line.substring("## ".length()).strip();
                body.setLength(0);
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        addSection(documents, fileName, heading, body);
        return List.copyOf(documents);
    }

    private static void addSection(List<Document> documents, String fileName, String heading, StringBuilder body) {
        // strip() 去掉標題與內文之間、段落之間那些空行
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        String source = heading == null ? fileName : fileName + " > " + heading;
        documents.add(new Document(source + "\n" + text, Map.of(SOURCE, source)));
    }

    private static String read(String fileName) {
        String path = DIRECTORY + fileName;
        try (InputStream in = MarkdownKnowledgeBase.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到知識庫檔案：" + path);
            }
            // 編碼一定要明寫。拿到的是 bytes，要用什麼解碼是這裡的決定 ——
            // 靠預設值的話，換一台預設編碼不同的機器，中文就會變成亂碼
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("讀不了知識庫檔案：" + path, e);
        }
    }
}
