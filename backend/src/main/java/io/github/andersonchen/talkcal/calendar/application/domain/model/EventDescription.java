package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 使用者用自然語言講的行程，還沒解析，例如「明天下午三點跟小明吃飯，週三早上看牙醫」。一句可以有好幾個行程。
 *
 * 空白在叫模型之前就擋下：送過去只是白叫一次模型。丟 IllegalArgumentException，
 * 呼叫端才分得出是自己送錯，還是模型出事（IllegalStateException）。
 */
public record EventDescription(String text) {

    public EventDescription {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("行程描述不可為 null 或空白");
        }
    }
}
