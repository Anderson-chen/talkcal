package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 使用者用自然語言描述的行程，還沒解析，例如「明天下午三點跟小明吃飯，週三早上看牙醫」。
 *
 * 「不可空白」這條規則要在呼叫模型之前就成立：空白的描述送過去只會白叫一次模型。
 * 在這裡先丟 IllegalArgumentException，呼叫端才分得出是自己送錯，還是模型出事（IllegalStateException）。
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
