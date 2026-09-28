package eat.conversation.application.domain.model;

/**
 * 使用者的一個提問，還沒加上任何參考資料。
 *
 * 它存在的理由是時間點：「提問不可空白」這條規則必須在檢索之前就成立。
 * 原本這條規則只寫在 GroundedQuestion 裡，而 GroundedQuestion 要等檢索完才建得出來 ——
 * 於是空白提問會先去驚動檢索，背後是另一台 embedding server。
 * 回 400 是因為 adapter 自己剛好也不收空白字串，那是碰巧，不是規則在起作用；
 * embedding server 沒開時，同一個空白提問換來的是 502。
 *
 * 把規則收進這個型別，AskQuestionService 一拿到提問就先建它，
 * 不合規的提問連檢索都走不到。
 */
public record Question(String text) {

    // 提問文字不可為 null 或空白（規則 4）
    public Question {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("提問文字不可為 null 或空白");
        }
    }
}
