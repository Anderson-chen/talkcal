package eat.calendar.adapter.out.extraction.llamacpp;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.EventDescription;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 對真正的 llama-server 跑，句子都是開發時實測過的那幾句。
 *
 * 這裡驗的不只是「HTTP 通了」，而是 prompt + 日期表 + schema 這整套對這顆模型有沒有效：
 * 日期表之前，「下週三」兩次都被算錯；跨夜的結束時間也被丟掉過。這幾個斷言就是那兩個坑。
 *
 * request 帶 temperature 0，同樣輸入同樣輸出，所以日期可以斷言得很死；
 * 標題仍然不斷言確切文字 —— 換一版模型措辭就可能變，那不是這裡要守的東西。
 * 這支測試第一次跑就抓到 0.7 溫度下「下週三」偶爾查錯，temperature 0 就是因此加的。
 * 哪天換了模型或改了 prompt，先跑這個。
 */
@DisplayName("LlamaCppExtractEventsAdapter（真實 server）")
class LlamaCppExtractEventsAdapterTest {

    private static final URI BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    // 2026-10-05 星期一 09:30
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 9, 30);

    @BeforeAll
    static void requireRunningServer() {
        Assumptions.assumeTrue(isHealthy(),
                () -> "llama-server 沒有在 " + BASE_URI + " 執行，跳過整合測試");
    }

    private static boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(BASE_URI.resolve("/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<CalendarEvent> extract(String text) {
        // 逾時跟 application.properties 的 llamacpp.connectTimeout / readTimeout 一致
        RestClient llamaCpp = RestClient.builder()
                .baseUrl(BASE_URI.toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(
                        HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofMinutes(2))))
                .build();
        return new LlamaCppExtractEventsAdapter(llamaCpp).extractEvents(new EventDescription(text), NOW);
    }

    @Test
    @DisplayName("一句兩個行程：拆成兩筆，「下週三」查表查對")
    void splitsAndResolvesNextWednesday() {
        List<CalendarEvent> events = extract("明天下午三點跟小明吃飯，下週三早上九點到十點半看牙醫");

        assertEquals(2, events.size());
        assertEquals(LocalDateTime.of(2026, 10, 6, 15, 0), events.get(0).start());
        // 沒講結束時間：domain 補一小時
        assertEquals(LocalDateTime.of(2026, 10, 6, 16, 0), events.get(0).end());
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), events.get(1).start());
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 30), events.get(1).end());
    }

    @Test
    @DisplayName("跨夜：結束時間用隔天的日期")
    void overnight() {
        List<CalendarEvent> events = extract("晚上十點到凌晨一點唱歌");

        assertEquals(1, events.size());
        assertEquals(LocalDateTime.of(2026, 10, 5, 22, 0), events.getFirst().start());
        assertEquals(LocalDateTime.of(2026, 10, 6, 1, 0), events.getFirst().end());
    }

    @Test
    @DisplayName("閒聊沒有行程：空清單")
    void smallTalk() {
        assertTrue(extract("今天天氣真好").isEmpty());
    }

    @Test
    @DisplayName("分類由模型判斷：吃飯是社交、看牙醫是健康、跟客戶開會是工作")
    void classifiesCategories() {
        List<CalendarEvent> events = extract("明天下午三點跟小明吃飯，下週三早上九點看牙醫，週五早上十點跟客戶開會");

        assertEquals(List.of(Category.SOCIAL, Category.HEALTH, Category.WORK),
                events.stream().map(CalendarEvent::category).toList());
    }

    @Test
    @DisplayName("地點：有講就抽出來、標題裡不留地點但保留「跟誰」；沒講就沒有，不會編一個")
    void extractsLocationOnlyWhenSaid() {
        CalendarEvent meeting = extract("週五早上十點在3F會議室A跟客戶開會").getFirst();
        CalendarEvent yoga = extract("晚上七點在健身房上瑜珈課").getFirst();
        CalendarEvent movie = extract("週六晚上去看電影").getFirst();


        assertEquals(Optional.of("3F會議室A"), meeting.location());
        assertTrue(meeting.title().contains("客戶"), meeting.title());
        assertEquals(Optional.of("健身房"), yoga.location());
        assertEquals(Optional.empty(), movie.location());
    }

    @Test
    @DisplayName("回報的 bug：「明天下午3點和 Amy 開會」沒講結束時間 → 15:00–16:00，不是 502、也不是模型編的時間")
    void unstatedEndFallsBackToDefault() {
        for (String said : List.of("明天下午3點和 Amy 開會", "明天下午3點跟小明吃飯")) {
            CalendarEvent event = extract(said).getFirst();

            assertEquals(LocalDateTime.of(2026, 10, 6, 15, 0), event.start(), said);
            assertEquals(LocalDateTime.of(2026, 10, 6, 16, 0), event.end(), said);
        }
    }
}
