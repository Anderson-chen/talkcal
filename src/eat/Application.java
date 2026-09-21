package eat;

import eat.conversation.adapter.in.console.ConsoleChatAdapter;
import eat.conversation.adapter.out.llamacpp.LlamaCppGenerateReplyAdapter;
import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.service.AskQuestionService;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.out.GenerateReplyPort;

import java.io.Console;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 組裝根（Composition Root）：整個專案唯一同時認識 adapter 和 application 的地方。
 *
 * 看一眼 import 就知道為什麼它只能有一個：
 * 上面同時出現了 adapter.in、adapter.out 和 application ——
 * 這種 import 組合在其他任何檔案裡出現，都代表分層漏了。
 *
 * 它也是唯一決定「用哪個實作」的地方。要換成 OpenAI 或 Claude，
 * 改的是下面那一行 new，AskQuestionService、Conversation、所有測試一個字都不用動。
 * 前面幾步鋪的所有介面，結論就在這一行上。
 *
 * 放在根 package（eat）而不是某個功能模組裡面，是刻意的：
 * 功能模組是 eat 底下的子 package（目前只有 conversation），
 * 組裝根必須站在所有模組的外面才有資格認識它們全部。
 * 這個位置也正是 Spring Modulith 用來偵測模組的起點，將來要接它不必再搬一次。
 */
public final class Application {

    // 預設打本機常駐的那台，需要時用 -Dllamacpp.baseUri=... 指到別的位址，不必改程式
    private static final URI LLAMA_CPP_BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    // 系統指令是組裝時的設定，不是 domain 的規則 ——
    // Conversation 只知道「指令只能設定一次」，至於內容寫什麼由這裡決定。
    // 跟 baseUri 一樣走系統屬性：啟動時就能換，不必改程式重編譯
    private static final String INSTRUCTION_PROPERTY = "chat.instruction";
    private static final String DEFAULT_INSTRUCTION = "用繁體中文簡潔回答。";

    private Application() {
    }

    public static void main(String[] args) {
        // 這三行就是全部的接線：真正的實作在左邊被換成介面，之後誰也看不到 llama.cpp
        GenerateReplyPort generateReplyPort = new LlamaCppGenerateReplyAdapter(LLAMA_CPP_BASE_URI);
        AskQuestionUseCase askQuestion = new AskQuestionService(generateReplyPort);
        ConsoleChatAdapter console = new ConsoleChatAdapter(askQuestion, consoleReader(), consoleWriter());

        console.run(startConversation());
    }

    /**
     * 建立這次執行要用的對話。
     *
     * 明確給空字串（-Dchat.instruction=）代表「這次不要系統指令」，
     * 走 Conversation.start() 的無參數版本讓模型保持預設人格 ——
     * 而不是把空白丟給 Conversation 讓它以「文字不可為空白」擋下來。
     * 那條規則是為了擋壞資料，不是為了擋一個合理的設定選擇。
     */
    private static Conversation startConversation() {
        String instruction = System.getProperty(INSTRUCTION_PROPERTY, DEFAULT_INSTRUCTION);
        return instruction.isBlank() ? Conversation.start() : Conversation.start(instruction);
    }

    /**
     * 從真正的終端機啟動時，System.console() 會給我們一組已經套好終端機編碼的 Reader / Writer，
     * 那是最不會出錯的來源 —— 在 Windows 上硬指定 UTF-8 反而會在 cp950 的主控台印出亂碼。
     *
     * 在 IDE 裡執行或輸出被導向檔案時，System.console() 是 null，
     * 這時 UTF-8 才是合理的預設（IntelliJ 的執行視窗就是 UTF-8）。
     */
    private static Reader consoleReader() {
        Console console = System.console();
        return console != null
                ? console.reader()
                : new InputStreamReader(System.in, StandardCharsets.UTF_8);
    }

    private static Writer consoleWriter() {
        Console console = System.console();
        return console != null
                ? console.writer()
                : new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
    }
}
