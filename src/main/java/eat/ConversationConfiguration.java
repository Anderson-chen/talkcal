package eat;

import eat.conversation.adapter.out.knowledge.KnowledgeBaseRetriever;
import eat.conversation.adapter.out.knowledge.MarkdownKnowledgeBase;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * conversation 模組的接線（calendar 那邊是 CalendarConfiguration）。
 *
 * conversation 沒有自己的 domain 了：對話紀錄、RAG、跟模型講話全部交給 Spring AI，
 * 這裡做的事就是把 Spring AI 的零件組成一個 ChatClient，交給 ChatController 用。
 *
 * 跟模型有關的 bean（ChatModel、EmbeddingModel、ChatMemory、ChatClient.Builder）都是 Spring AI 依
 * application.properties 的 spring.ai.* 自動建的 —— 連到哪、等多久、生成上限是設定，不是程式。
 * 這裡只決定「怎麼組」：
 * - 對話紀錄：MessageChatMemoryAdvisor + 自動組裝的 ChatMemory（PostgreSQL、只留最近 20 則）
 * - RAG：RetrievalAugmentationAdvisor + 知識庫的 retriever
 *
 * 放在根 package eat、類別不 public、proxyBeanMethods = false，理由跟以前一樣：
 * 組裝根站在所有模組外面，沒有程式該直接引用它，Bean 方法之間也不互相呼叫。
 */
@Configuration(proxyBeanMethods = false)
class ConversationConfiguration {

    // 檢索參數。原本的規則是「地板 0.40 + 相對門檻 0.9 + 前 3 筆」，
    // Spring AI 的 VectorStoreDocumentRetriever 只有絕對門檻和 topK，相對門檻拿掉了 ——
    // 代價是「同一份文件但不同主題」的段落比較容易一起被撈進來。
    // 0.40 是對 bge-m3 + 這份知識庫量出來的（不相干 0.27～0.32、相關但抽象 0.50～0.56），
    // 換 embedding 模型就得重量，RetrievalQualityTest 守著它。
    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.40;

    // 參考資料怎麼跟問題組在一起。{context} 和 {query} 是 Spring AI 填的兩個空格。
    // 措辭照抄原本的 GroundedQuestion：防幻覺那句話夾在資料之後、問題之前 ——
    // 放最前面會被大量資料淹掉，放問題之後又離問題太遠。
    // 「問題：」是分隔線，免得模型把提問當成參考資料的最後一段。
    private static final String GROUNDED_QUESTION = """
            參考資料：
            {context}

            請依據上述參考資料回答，資料中沒有提到的內容不要自行推測。

            問題：{query}""";

    /**
     * 知識庫：markdown 切好段、第一次提問才算向量放進記憶體裡的 SimpleVectorStore。
     *
     * 要換成別種檢索（向量資料庫、全文搜尋、加 rerank），動的就是這個 Bean。
     */
    @Bean
    DocumentRetriever knowledgeBase(EmbeddingModel embeddingModel) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        DocumentRetriever byVector = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();
        return new KnowledgeBaseRetriever(vectorStore, MarkdownKnowledgeBase.documents(), byVector);
    }

    /**
     * 聊天用的 ChatClient：每一題先帶上這段對話的歷史，再把檢索到的片段組進提問。
     *
     * 兩個 advisor 的先後由 order 決定（數字小的先處理請求），RAG 的 order 明寫成「緊跟在記憶後面」：
     * 記憶那一層先拿到請求，存進資料庫的是使用者原本那句話；RAG 接著才把參考資料組進這一次的提問，
     * 帶著參考資料的版本只送給模型，不會一輪一輪疊進歷史。
     * 不靠 RAG 的預設 order：預設值沒寫在文件上，哪天改了，記憶就會存到組好的長提問。
     *
     * 沒檢索到東西時（allowEmptyContext）照原本的提問送出去，讓模型自由發揮：
     * Spring AI 預設是叫模型回「我不知道」，但檢索漏掉不代表模型自己不知道。
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, DocumentRetriever knowledgeBase) {
        RetrievalAugmentationAdvisor rag = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(knowledgeBase)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .promptTemplate(new PromptTemplate(GROUNDED_QUESTION))
                        .documentFormatter(ConversationConfiguration::numbered)
                        .allowEmptyContext(true)
                        .build())
                .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1)
                .build();
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), rag)
                .build();
    }

    // 片段編號：讓模型有辦法在回覆裡指名出處（「根據 [2]」）。
    // 每個片段的第一行就是出處（MarkdownKnowledgeBase 把它接在內文前面），所以不必另外印
    private static String numbered(List<Document> documents) {
        return IntStream.range(0, documents.size())
                .mapToObj(i -> "[" + (i + 1) + "]" + documents.get(i).getText())
                .collect(Collectors.joining("\n"));
    }
}
