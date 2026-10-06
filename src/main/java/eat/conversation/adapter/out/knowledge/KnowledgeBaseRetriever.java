package eat.conversation.adapter.out.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Objects;

/**
 * 從知識庫撈出跟提問相關的片段：Spring AI 的 DocumentRetriever，只多做一件事 —— 第一次被問才建索引。
 *
 * 怎麼挑（topK、相似度門檻）完全交給交進來的 delegate（VectorStoreDocumentRetriever）。
 * 這裡存在的理由只有啟動時機：把 Document 放進 VectorStore 就要呼叫 embedding server，
 * 如果在建 bean 的時候放，embedding server 沒開，整個應用就起不來 ——
 * 連跟模型無關的行事曆列表、/actuator/health 都一起掛掉。
 * 延到第一次提問：server 沒開就是那一題失敗（502），等它起來再問一次就好。
 */
public final class KnowledgeBaseRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final List<Document> documents;
    private final DocumentRetriever delegate;

    // 只在 ensureIndexed() 的 synchronized 區塊裡讀寫，所以不需要 volatile
    private boolean indexed;

    /**
     * delegate 必須是從同一個 vectorStore 撈東西的 retriever，否則這裡建好的索引沒人看得到。
     */
    public KnowledgeBaseRetriever(VectorStore vectorStore, List<Document> documents, DocumentRetriever delegate) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore 不可為 null");
        this.documents = List.copyOf(Objects.requireNonNull(documents, "documents 不可為 null"));
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可為 null");
    }

    @Override
    public List<Document> retrieve(Query query) {
        ensureIndexed();
        return delegate.retrieve(query);
    }

    // synchronized：兩題同時是第一題時，只有一題去算向量，另一題等它算完。
    // 算到一半失敗（embedding server 斷了）就不標成已索引，下一題會整份重來。
    // 重來不會讓片段重複：每次放的都是同一批 Document 物件，id 一樣，SimpleVectorStore 照 id 覆蓋
    private synchronized void ensureIndexed() {
        if (!indexed) {
            vectorStore.add(documents);
            indexed = true;
        }
    }
}
