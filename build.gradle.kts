plugins {
    java
    // 讓 ./gradlew run 能直接啟動組裝根
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        // 指定編譯用的 JDK 版本，而不是「拿執行 Gradle 的那個 JVM 將就」——
        // 換一台機器、換一個 IDE 都編出同樣的結果。
        // 25 是這台機器上裝的版本（也是 LTS）；程式用到的語法 21 起就支援
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // BOM 統一管理 JUnit 各模組的版本，下面就不必逐一寫版號
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // 執行期才需要的啟動器；沒有它 Gradle 找不到測試引擎
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 程式與註解都有中文，編碼一定要明寫。
// 不寫的話 javac 會用平台預設，在 Windows 上是 cp950，中文註解會編成亂碼
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform {
        // 預設跳過需要真 llama-server 的整合測試，讓 ./gradlew test 是秒回的。
        // 要連它們一起跑：./gradlew test -PwithIntegration
        if (!project.hasProperty("withIntegration")) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass = "eat.Application"
}

tasks.named<JavaExec>("run") {
    // Gradle 預設不把主控台的輸入接給被執行的程式，
    // 少了這行 ConsoleChatAdapter 一啟動就讀到 EOF 直接結束
    standardInput = System.`in`
}
