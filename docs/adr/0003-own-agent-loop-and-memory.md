# 0003. AI 助理的 agent loop 自己寫，對話記憶連工具呼叫一起存

- 狀態：已採用
- 日期：2026-10-06
- 相關：[3eee98a 行事曆加 AI 助理 agent](https://github.com/Anderson-chen/talkcal/commit/3eee98a)、[0001](0001-spring-ai-openai-compatible.md)

## 背景

AI 助理有三個工具（`propose_events`、`list_events`、`find_free_slots`），模型自己決定要不要呼叫。
當時聊天功能已經用 Spring AI 的 `ChatClient` + `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository`（V6）。

照搬到助理之後出現的 bug：使用者要改時間，助理回「已顯示卡片」，畫面上卻沒有卡片。
原因是 `JdbcChatMemoryRepository` 存檔時把「帶工具呼叫的助理訊息」和「工具結果」濾掉，
下一輪模型看到的歷史是「使用者說了時間 → 助理說『已顯示卡片』」，就學會只說不做（實測三次都沒呼叫工具）。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| `ChatClient` + advisor + Spring AI 的 JDBC 記憶 | 幾乎不用寫程式 | 工具訊息被濾掉，模型只說不做；每則只存文字 |
| 自己跑迴圈（`ChatModel` + `ToolCallingManager`），記憶自己存 | 每一則訊息都在手上，存什麼、截到哪都看得見 | 迴圈、步數上限、記憶截斷、存檔都要自己寫和測 |

## 決定

**`CalendarAssistant` 自己跑 agent loop（最多 4 步），記憶存在自己的表 `calendar_assistant_message`，工具呼叫和結果用 JSONB 存。**

歷史裡留著工具呼叫，同一段對話三次都正確呼叫工具（[V7](../../backend/src/main/resources/db/migration/V7__create_calendar_assistant_message.sql) 開頭的註解）。

分工照實測 qwen3:8b 定：模型負責判斷意圖、時間不確定時反問、把對話整理成一句話；
時間解析、空檔計算、「下午」是幾點、存不存檔由程式和使用者決定。模型的話也不全信：沒呼叫工具卻說「已加入」就要它重來。

## 代價

- 迴圈和記憶是自己的程式，要自己測（測試用假模型照劇本呼叫工具）。
- 存檔是整段刪掉重插：同一段對話同時送兩句，晚存的蓋掉早的（V7 寫明接受，epic [#46](https://github.com/Anderson-chen/talkcal/issues/46) 的「並行修改」）。

## 什麼時候重想

- Spring AI 的記憶庫改成保留工具訊息 → 可以考慮換回現成的。
- 助理要的步驟變多、或要多個 agent 協作 → 重新評估框架。

## 證據

- [CalendarAssistant](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/in/assistant/CalendarAssistant.java)：類別註解寫了為什麼不交給 ChatClient
- [V7__create_calendar_assistant_message.sql](../../backend/src/main/resources/db/migration/V7__create_calendar_assistant_message.sql)：為什麼不用 V6 那張表
- [JdbcAssistantMemoryRepository](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/JdbcAssistantMemoryRepository.java)
