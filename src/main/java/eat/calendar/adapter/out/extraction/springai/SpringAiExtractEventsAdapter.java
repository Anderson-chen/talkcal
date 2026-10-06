package eat.calendar.adapter.out.extraction.springai;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.EventDescription;
import eat.calendar.application.port.out.ExtractEventsPort;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 用 Spring AI 的 ChatModel 把自然語言抽成行程。
 *
 * ChatModel 是 Spring AI 依 spring.ai.openai.* 自動組裝的那一個（跟聊天共用）：
 * 連到哪、等多久、HTTP 怎麼送都是它的事，這裡不再有一行 HTTP 程式碼。
 * 留在這裡的只有「這件工作」的知識，分在兩塊 package-private 的零件裡：
 * ExtractionRequest 組 prompt（日期表、JSON Schema、溫度 0），
 * ExtractionResponse 把模型輸出翻成 CalendarEvent（含結束時間的核對）。
 *
 * 只收 ChatModel 這個介面，不收 OpenAiChatModel：換成別家的 ChatModel 也接得上，
 * 要改的只有 ExtractionRequest.options() 那份供應商專屬的選項。
 */
public final class SpringAiExtractEventsAdapter implements ExtractEventsPort {

    private final ChatModel chatModel;

    public SpringAiExtractEventsAdapter(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不可為 null");
    }

    @Override
    public List<CalendarEvent> extractEvents(EventDescription description, LocalDateTime now) {
        ChatResponse response;
        try {
            response = chatModel.call(ExtractionRequest.prompt(description, now));
        } catch (RuntimeException e) {
            // port 的契約是失敗丟 IllegalStateException（CalendarController 翻成 502）。
            // ChatModel 失敗時丟的是 OpenAI SDK 自己的例外，而且型別隨失敗原因不同（連不上、逾時、HTTP 4xx/5xx），
            // 全部包成同一種；保留 cause，log 裡才看得到真正的原因
            throw new IllegalStateException("呼叫模型抽行程失敗：" + e.getMessage(), e);
        }
        return ExtractionResponse.events(response, description);
    }
}
