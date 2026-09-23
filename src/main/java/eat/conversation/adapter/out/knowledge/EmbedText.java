package eat.conversation.adapter.out.knowledge;

/**
 * 把一段文字變成向量。
 *
 * 這不是 port —— core 永遠不會認識它。它是檢索 adapter 內部的接縫，存在的理由有兩個：
 * 1. 讓「算餘弦、排序、取前幾筆」那些邏輯不必開 embedding server 就能測。
 * 2. 換 embedding 供應商（llama.cpp、OpenAI、本機 ONNX）時，只換這個介面的實作。
 *
 * 實作必須保證：同一個實作回傳的向量維度一律相同 ——
 * 索引知識庫和查詢用的必須是同一個模型，不同模型的向量空間完全不相通。
 */
public interface EmbedText {

    // 呼叫失敗時丟出非受檢例外，與其他 outbound adapter 的約定一致
    float[] embed(String text);
}
