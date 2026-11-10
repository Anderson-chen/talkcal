# backend —— Spring Boot app

程式碼、Gradle、Dockerfile。怎麼跑、怎麼測見根目錄的 [README.md](../README.md#怎麼跑)；為什麼選這些技術見 [docs/adr/](../docs/adr/README.md)。

## 程式結構

後端是六角架構（Hexagonal / Ports & Adapters），目錄照 BuckPal 的慣例：

```
io.github.andersonchen.talkcal
├── Application            只負責啟動（留在根 package，元件掃描和測試都從這裡找起）
├── app                    組裝根，代表整個應用程式：業務模組不知道自己被怎麼組起來
│   ├── config             所有 @Configuration，唯一認識具體實作的地方
│   ├── job                排程工作（每天清舊對話）
│   └── observability      全站共用的 access log
└── calendar               行事曆這門業務，一個模組
    ├── application
    │   ├── domain.model     業務規則（CalendarEvent、FreeSlots…），純 Java，不認識 Spring
    │   ├── domain.service   use case 的實作，只呼叫 model 和 port
    │   ├── port.in          use case 介面（外面怎麼使喚核心）
    │   └── port.out         核心需要的外部能力（存取行程、抽行程）
    └── adapter
        ├── in               HTTP controller、AI 助理與它的工具
        └── out              PostgreSQL、Spring AI
```

分層規則寫成 [ArchitectureTest](src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)（ArchUnit）：
依賴只能由外往內、core 不准依賴 Spring 和 Jackson、只有組裝根（app.config、app.job）能認識 adapter 的具體類別、模組之間互不 import。違反就紅燈，不靠 code review 記得。

## 技術選型

| 用什麼 | 為什麼 |
|---|---|
| Java 25、Spring Boot 4.1、Spring MVC | |
| Spring AI 2.0（OpenAI 相容協定）跟模型講話 | 本機的 llama-server 和雲端的 Bedrock 都講 OpenAI 的協定，換模型只改設定、不改程式 |
| 模型：本機 llama.cpp + Qwen3-8B（Q4_K_M）；雲端 Bedrock 的 Qwen3 32B | 本機自架：資料不出機器、沒有 token 費用。雲端先走 Bedrock：GPU 配額被拒，理由見 [infra/envs/1-ec2](../infra/envs/1-ec2/README.md#為什麼先走-bedrock) |
| PostgreSQL 18 + Flyway，用 JdbcClient 不用 JPA | 表不多、查詢單純，手寫 SQL 一眼看得懂，domain 物件不必為了 ORM 加註解；完整理由見 [ADR 0004](../docs/adr/0004-jdbc-client-not-jpa.md) |

前端、觀測、壓測、部署的選型寫在各自的 README：[web/](../web/README.md#為什麼這樣選)、[ops/](../ops/README.md#選型理由)、[perf/](../perf/README.md)、[deploy/](../deploy/README.md)。每個重大決定的選項和代價在 [docs/adr/](../docs/adr/README.md)。
