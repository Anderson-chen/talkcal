package io.github.andersonchen.talkcal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 應用程式入口：把 Spring 容器啟起來，就這樣。
 *
 * 這個類別刻意不認識任何 adapter、也不認識任何 port ——
 * 一個 import 都沒有從 io.github.andersonchen.talkcal.calendar 進來。
 * 「哪個介面用哪個實作」那些決定集中在 app.config（一個模組一個 Configuration，目前只有 CalendarConfiguration）。
 * 拆開的理由是責任不同：這裡回答「怎麼啟動」，那邊回答「誰接誰」。
 * 模組變多時，app.config 會多幾個 Configuration，這裡永遠只有 main。
 *
 * 根 package 底下除了這個類別，只有兩種東西：
 *   app       —— 組裝根，代表「整個應用程式」：把各業務模組組起來、加上全站共用的東西
 *     config         所有 @Configuration，唯一認識具體實作的地方
 *     job            排程工作：app 背地裡自己會做的事
 *     observability  不屬於任何業務的觀測（access log）
 *   calendar  —— 業務模組（application + adapter），不含接線
 * 跟 cool-down 的 app 模組同一個切法：業務模組不知道自己被誰、怎麼組起來。
 *
 * 那為什麼這個類別不也放進 app：它的位置有兩個技術上的作用，搬了就壞——
 * 元件掃描以「這個類別所在的 package」為根往下掃，放進 app 就掃不到 calendar；
 * @SpringBootTest、@WebMvcTest 從測試所在的 package 往上找它，calendar 底下的測試會找不到。
 * cool-down 的 Application 也一樣留在根 package（com.cooldown），只是檔案放在 app 模組裡。
 * 這個位置也正是 Spring Modulith 用來偵測模組的起點，將來要接它不必再搬一次。
 *
 * Spring 只待在最外這一圈。往內的 application/domain 全是純 Java，連一個 Component 都沒有 ——
 * ArchitectureTest 有一條規則守著這件事：core 不准依賴 org.springframework。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
