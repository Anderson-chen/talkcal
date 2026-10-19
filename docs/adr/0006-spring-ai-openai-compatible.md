# 0006. 跟模型講話用 Spring AI 的 OpenAI 相容 client

- 狀態：已採用
- 日期：2026-10-06
- 相關：[e5ae6dc 導入 Spring AI](https://github.com/Anderson-chen/talkcal/commit/e5ae6dc)、[0002](0002-model-bedrock-default-gpu-optional.md)、[0007](0007-self-hosted-llama-cpp-qwen3-8b.md)

## 背景

一開始是自己寫的 llama.cpp adapter：先是手寫 JSON（[8f5ae30](https://github.com/Anderson-chen/talkcal/commit/8f5ae30)），
後來換 Jackson、再換成組裝根建好的 `RestClient`（[f881812](https://github.com/Anderson-chen/talkcal/commit/f881812)）。
每多一個需求（JSON Schema 約束輸出、工具呼叫、對話記憶）就要自己翻譯一次協定。

llama-server 講的是 OpenAI 的 Chat Completions 協定，雲端的託管模型多半也講這套。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| 繼續自己寫 adapter（RestClient + Jackson） | 每個位元組都看得見；`RestClient` 自帶 HTTP span 和 `http.client.requests` 指標 | 工具呼叫、記憶、結構化輸出全部要自己做 |
| Spring AI 的 OpenAI 模組 | `ChatModel`、`ToolCallingManager`、`OpenAiChatOptions`（JSON Schema）現成；換模型只換位址、key、模型名稱 | 多一層框架；底層是 openai-java SDK（OkHttp），原本 RestClient 的 HTTP span 和 `traceparent` 沒了 |

## 決定

**用 Spring AI 2.0 的 `spring-ai-starter-model-openai`，`base-url` 指向 llama-server 的 `/v1`。**

行事曆的抽取改用 `ChatModel`（`SpringAiExtractEventsAdapter`），AI 助理用 `ChatModel` + `ToolCallingManager`。
連到哪、用哪顆模型全在 [application.properties](../../backend/src/main/resources/application.properties)，程式不知道對面是誰。

這個決定後來真的被用上：階段一 GPU 配額被拒、改走 Bedrock 時，
[compose.bedrock.yaml](../../infra/envs/1-ec2/compose.bedrock.yaml) 只用環境變數換掉位址、key、模型名稱，app 一行沒改。

## 代價

- 往外的 HTTP span 沒了，改看 Spring AI 自己的 `gen_ai.*` 觀測：多了 token 用量，少了 HTTP 狀態碼（[ops/README.md](../../ops/README.md) 的「追蹤」一節）。
- Spring AI 現成的記憶（`JdbcChatMemoryRepository`、`MessageChatMemoryAdvisor`）不適合 agent，助理只用它的底層零件，迴圈自己寫（見 [0008](0008-own-agent-loop-and-memory.md)）。
- `base-url` 一定要帶 `/v1`，少了會打到 llama.cpp 原生格式的端點（寫在 application.properties 的註解）。

## 什麼時候重想

- 雲端想拿掉長期 API key → 改用 Spring AI 的 Bedrock Converse 模組，直接吃機器或 Pod 的角色（[infra/envs/1-ec2/README.md](../../infra/envs/1-ec2/README.md)）。
- 要接的模型不講 OpenAI 協定，或 Spring AI 跟不上 Spring Boot 的版本。

## 證據

- [application.properties](../../backend/src/main/resources/application.properties)：`spring.ai.openai.chat.base-url`、換成真的 OpenAI 怎麼改的註解
- [SpringAiExtractEventsAdapter](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/out/extraction/springai/)
- [compose.bedrock.yaml](../../infra/envs/1-ec2/compose.bedrock.yaml)：換模型只靠環境變數
