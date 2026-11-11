# backend：Spring Boot app

後端的程式碼、Gradle 設定和 Dockerfile。安裝、執行見根目錄的 [README.md](../README.md#快速開始)，測試見 [docs/testing.md](../docs/testing.md)。

## 技術選型

| 技術 | 選用理由 |
|---|---|
| Spring AI 2.0（OpenAI 相容協定）跟模型講話 | 本機的 llama-server 和雲端的 Bedrock 都講 OpenAI 的協定，換模型只要改設定 |
| 模型：本機 llama.cpp + Qwen3-8B（Q4_K_M）；雲端 Bedrock 的 Qwen3 32B | 本機自架，資料不出機器，也沒有 token 費用。雲端的 GPU 配額被拒，所以先用 Bedrock，見 [infra/envs/1-ec2](../infra/envs/1-ec2/README.md#為什麼先走-bedrock) |
| PostgreSQL 18 + Flyway，用 JdbcClient 不用 JPA | 表不多、查詢單純，手寫 SQL 一看就懂，domain 物件也不用為了 ORM 加註解。見 [ADR 0004](../docs/adr/0004-jdbc-client-not-jpa.md)。每張表為什麼長這樣，寫在 [db/migration](src/main/resources/db/migration) 每支 SQL 的開頭 |

## 程式結構

後端是六角架構（Hexagonal / Ports & Adapters），目錄照 BuckPal 的慣例：

```
io.github.andersonchen.talkcal
├── Application            只負責啟動（留在根 package，元件掃描和測試都從這裡找起）
├── app                    組裝根，代表整個應用程式：業務模組不知道自己被怎麼組起來
│   ├── config             所有 @Configuration，誰接誰都寫在這裡
│   ├── job                排程工作：歸檔、清理舊資料
│   └── observability      全站共用的 access log
├── calendar               行事曆和 AI 助理，一個模組
│   ├── application
│   │   ├── domain.model     業務規則（CalendarEvent、FreeSlots…），純 Java，不認識 Spring
│   │   ├── domain.service   use case 的實作，只呼叫 model 和 port
│   │   ├── port.in          use case 介面：外面怎麼叫核心做事
│   │   └── port.out         核心需要的外部能力：存取行程、抽行程、歸檔、記用量
│   └── adapter
│       ├── in               HTTP controller、AI 助理與它的工具、放在 /actuator/prometheus 的活躍使用者數
│       ├── out              PostgreSQL、Spring AI、S3、Micrometer
│       └── shared           in、out 兩側都要的模型零件：日期對照表、斷路器
└── auth                   帳號和登入，一個模組
    ├── application
    │   ├── domain.model     帳號的規則（Account、Username…），純 Java
    │   ├── domain.service   use case 的實作
    │   ├── port.in          use case 介面：註冊、找帳號、用 Google 或 GitHub 登入
    │   └── port.out         存取帳號、算密碼雜湊
    └── adapter
        ├── in               HTTP controller、Spring Security 登入時對到帳號
        └── out              PostgreSQL、用 Spring Security 算密碼雜湊
```

分層規則用 ArchUnit 寫成 [ArchitectureTest](src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)，違反就紅燈，不用靠 code review 記得：

- 依賴只能由外往內。
- core 不能依賴框架和外部函式庫，例如 Spring、Jackson。
- 只有組裝根（app.config、app.job）能認識 adapter.out 的具體類別。
- 模組之間不互相 import。
