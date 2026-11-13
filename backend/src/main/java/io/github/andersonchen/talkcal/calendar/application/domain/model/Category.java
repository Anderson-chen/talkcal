package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 四種照設計稿來。設計稿裡的「假日」沒收進來：它只用在全天行程（國慶日），全天行程決定不做。
 *
 * 線路上怎麼寫是各個 adapter 的事：HTTP 用大寫字串，LLM 的 schema 用小寫，畫面上用中文。
 */
public enum Category {
    WORK,
    PERSONAL,
    HEALTH,
    SOCIAL;

    /**
     * 舊資料（V5 之前存的）、只給標題和時間的呼叫端，都落在這一類。
     * 選「個人」是因為它最不帶假設：把一件私事標成「工作」，比反過來更讓人困惑。
     */
    public static final Category DEFAULT = PERSONAL;
}
