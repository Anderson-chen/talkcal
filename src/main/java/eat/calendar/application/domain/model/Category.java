package eat.calendar.application.domain.model;

/**
 * 行程的分類：月曆上用顏色區分、側欄可以篩選。
 *
 * 四種照設計稿來；設計稿裡的「假日」沒有收進來 —— 它只用在全天行程（國慶日），而全天行程決定不做。
 *
 * 分類是業務概念，名稱怎麼在線路上呈現（"WORK"、"work"、「工作」）是各個 adapter 的事：
 * HTTP 那邊用大寫字串，LLM 的 schema 用小寫，畫面上用中文。這裡只有 enum 本身。
 */
public enum Category {
    WORK,
    PERSONAL,
    HEALTH,
    SOCIAL;

    /**
     * 沒指定分類時算哪一類。跟「沒講結束時間就算一小時」同一種業務預設：
     * 舊資料（V5 之前存的）、只給標題和時間的呼叫端，都落在這裡。
     * 選「個人」是因為它最不帶假設：把一件私事標成「工作」比反過來更讓人困惑。
     */
    public static final Category DEFAULT = PERSONAL;
}
