package eat.calendar.application.domain.model;

/**
 * 使用者用自然語言描述的行程，還沒解析，例如「明天下午三點跟小明吃飯，週三早上看牙醫」。
 *
 * 存在的理由跟 conversation 的 Question 一樣：「不可空白」這條規則必須在呼叫模型之前就成立。
 * 解析的背後是一台 GPU 上的 LLM，空白的描述送過去只會白燒一次推論，
 * 而且模型沒開時換來的是 502，而不是該有的 400。
 *
 * 一句話裡可以有好幾個行程：拆成幾筆是解析的結果，不是這裡的規則。
 */
public record EventDescription(String text) {

    public EventDescription {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("行程描述不可為 null 或空白");
        }
    }
}
