package eat.calendar.adapter.out.extraction.llamacpp;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.EventDescription;
import eat.calendar.application.port.out.ExtractEventsPort;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 用 llama.cpp 的 /v1/chat/completions 把自然語言抽成行程。
 *
 * 跟 conversation 的 LlamaCppGenerateReplyAdapter 是同一種結構：這個 public class 是唯一入口，
 * 兩塊翻譯零件（ExtractionRequest、ExtractionResponse）package-private，只在這裡組起來。
 *
 * 下面 send() 那段 HTTP 跟那個 adapter 幾乎一字不差 —— 這是刻意接受的重複：
 * 兩個模組不准互相 import（ArchitectureTest 的 modulesMustNotDependOnEachOther），
 * 為了二十行去開一個共用模組不划算。等第三份出現，再抽成一個「llama.cpp client」的共用元件。
 *
 * 連到哪、等多久由組裝根決定（交進來的 RestClient 已經設好 base URL 和逾時）；
 * 打哪個路徑、送什麼、錯誤怎麼翻，是這裡的知識。
 */
public final class LlamaCppExtractEventsAdapter implements ExtractEventsPort {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final RestClient llamaCpp;

    public LlamaCppExtractEventsAdapter(RestClient llamaCpp) {
        this.llamaCpp = Objects.requireNonNull(llamaCpp, "llamaCpp 不可為 null");
    }

    @Override
    public List<CalendarEvent> extractEvents(EventDescription description, LocalDateTime now) {
        return ExtractionResponse.events(send(ExtractionRequest.body(description, now)));
    }

    private String send(String requestBody) {
        try {
            byte[] responseBody = llamaCpp.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(JSON_UTF8)
                    // 送 byte、讀 byte，編碼由我們決定（UTF-8）。理由見 LlamaCppGenerateReplyAdapter：
                    // 交給轉換器的話，Windows 上可能用 cp950，中文到了伺服器那頭就是亂碼
                    .body(requestBody.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new IllegalStateException("llama.cpp 回應 HTTP " + response.getStatusCode().value()
                                + "：" + preview(readUtf8(response.getBody().readAllBytes())));
                    })
                    .body(byte[].class);
            return readUtf8(responseBody);
        } catch (RestClientException e) {
            throw new IllegalStateException("呼叫 llama.cpp 失敗（" + CHAT_COMPLETIONS_PATH + "）：" + e.getMessage(), e);
        }
    }

    private static String readUtf8(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    private static String preview(String body) {
        if (body.isEmpty()) {
            return "(沒有內容)";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截斷)";
    }
}
