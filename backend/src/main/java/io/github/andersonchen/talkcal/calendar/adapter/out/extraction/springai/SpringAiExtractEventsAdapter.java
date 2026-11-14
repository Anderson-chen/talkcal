package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import io.github.andersonchen.talkcal.calendar.adapter.shared.ModelUnavailableException;
import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * ChatModel 由組裝根注入，連到哪、等多久、HTTP 怎麼送都在 ChatModel 的設定裡。這裡只有抽行程這件工作的寫法：
 * ExtractionRequest 組 prompt（日期表、JSON Schema、溫度 0），ExtractionResponse 把模型輸出轉成 CalendarEvent。
 *
 * 只收 ChatModel 介面，不收 OpenAiChatModel：換成別家的 ChatModel 也能用，要改的只有 ExtractionRequest.options() 的選項。
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
            response = chatModel.call(ExtractionRequest.prompt(description, now, chatModel.getOptions()));
        } catch (ModelUnavailableException e) {
            // 本來就是 IllegalStateException，不包第二層：助理的工具框架靠這個型別判斷要不要原樣丟出
            // （見 CalendarAssistant 的 toolCallingManager），包了就判斷不出來
            throw e;
        } catch (RuntimeException e) {
            // ChatModel 失敗時丟 OpenAI SDK 自己的例外，型別隨失敗原因不同（連不上、逾時、HTTP 4xx/5xx），
            // 全部包成 port 約定的 IllegalStateException；保留 cause，log 裡才看得到真正的原因
            throw new IllegalStateException("呼叫模型抽行程失敗：" + e.getMessage(), e);
        }
        return ExtractionResponse.events(response, description);
    }
}
