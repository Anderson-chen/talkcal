package adapter.in.console;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.port.in.AskQuestionUseCase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 用主控台跟模型對話（inbound adapter）。
 *
 * 跟 adapter/out 那半邊對稱：那邊是「core 呼叫外面」，這邊是「外面呼叫 core」。
 * 六角的兩側到這裡才補齊。
 *
 * 職責刻意很窄：讀一行、交給 UseCase、印出結果。
 * 它不判斷任何業務規則 —— 提問與回覆要交替、文字不可空白、失敗要撤回提問，
 * 全部在 Conversation 和 AskQuestionService 裡，這裡只負責把人講的話送進去。
 *
 * 收 Reader / Writer 而不是直接用 System.in / System.out：
 * 一來測試可以餵 StringReader、收 StringWriter，不必真的開一個主控台；
 * 二來「用什麼編碼跟終端機講話」是組裝時才知道的事，屬於組裝根，不屬於這裡。
 */
public final class ConsoleChatAdapter {

    // 大小寫都接受，避免使用者打 EXIT 卻沒反應
    private static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit");

    private final AskQuestionUseCase askQuestion;
    private final BufferedReader in;
    private final PrintWriter out;

    public ConsoleChatAdapter(AskQuestionUseCase askQuestion, Reader in, Writer out) {
        this.askQuestion = Objects.requireNonNull(askQuestion, "askQuestion 不可為 null");
        this.in = new BufferedReader(Objects.requireNonNull(in, "in 不可為 null"));
        this.out = new PrintWriter(Objects.requireNonNull(out, "out 不可為 null"));
    }

    /**
     * 一直問到使用者離開為止。
     * conversation 由組裝根建立並傳進來 —— 系統指令要設什麼是組裝時的決定，不是這裡的。
     */
    public void run(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation 不可為 null");

        writeLine("輸入問題開始對話，輸入 exit 離開。");
        while (true) {
            String line = readLine();
            // null 代表輸入結束（Ctrl+Z 或管線讀完），跟打 exit 一樣是正常收工
            if (line == null || isExitCommand(line)) {
                break;
            }
            // 空行忽略：不小心按到 Enter 不該把整段對話結束掉
            if (line.isBlank()) {
                continue;
            }
            ask(conversation, line);
        }
        writeLine("結束對話。");
    }

    private void ask(Conversation conversation, String question) {
        try {
            Reply reply = askQuestion.askQuestion(conversation, question);
            writeLine(reply.text());
        } catch (RuntimeException e) {
            // AskQuestionService 失敗時已經撤回提問，對話回到提問前的狀態，
            // 所以這裡只要告訴使用者、讓他再問一次就好，不必自己收拾狀態
            writeLine("（沒拿到回覆：" + e.getMessage() + "。可以再問一次。）");
        }
    }

    private boolean isExitCommand(String line) {
        return EXIT_COMMANDS.contains(line.trim().toLowerCase(Locale.ROOT));
    }

    private String readLine() {
        // 提示符後面一定要 flush：PrintWriter 不會自己把沒有換行的內容送出去，
        // 少了這行使用者會對著空白畫面發呆
        out.print("> ");
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            // 連標準輸入都讀不到時沒有合理的復原方式，直接讓它往上炸
            throw new UncheckedIOException("讀取輸入失敗", e);
        }
    }

    private void writeLine(String text) {
        out.println(text);
        out.flush();
    }
}
