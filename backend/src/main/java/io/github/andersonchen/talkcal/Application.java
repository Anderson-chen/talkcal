package io.github.andersonchen.talkcal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 只負責啟動 Spring：不 import 任何 adapter 或 port，哪個 port 接哪個實作都在 app.config。
 *
 * 留在根 package，不搬進 app：元件掃描從這個類別所在的 package 往下掃，搬進 app 就掃不到 calendar、auth；
 * 測試的 @SpringBootTest、@WebMvcTest 從測試所在的 package 往上找這個類別，calendar 底下的測試也會找不到。
 *
 * Spring 只出現在 adapter 和 app 這一圈，application 層不依賴 org.springframework（ArchitectureTest 守著）。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
