package eat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 應用程式入口：把 Spring 容器啟起來，就這樣。
 *
 * 這個類別刻意不認識任何 adapter、也不認識任何 port ——
 * 一個 import 都沒有從 eat.conversation 進來。
 * 「哪個介面用哪個實作」那些決定住在同一個 package 的 Configuration 裡
 * （目前是 {@link ConversationConfiguration}）。
 * 拆開的理由是責任不同：這裡回答「怎麼啟動」，那邊回答「誰接誰」。
 * 模組變多時，那邊會一個模組一個 Configuration，這裡永遠只有 main。
 *
 * 放在根 package（eat）而不是某個功能模組裡面，是刻意的：
 * 功能模組是 eat 底下的子 package（目前只有 conversation），
 * 而元件掃描是以「這個類別所在的 package」為根往下掃 ——
 * 接線用的那些 Configuration 放在 eat 底下，才會被撿進容器。
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
