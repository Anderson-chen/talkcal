package io.github.andersonchen.talkcal.quota.application.domain.model;

/** 一份計數照什麼分開算。資料庫存的是 id()（見 V15 的 quota_bucket.subject） */
public enum QuotaSubject {
    /** 同一個 IP（IPv6 照 /64）共用一份：訪客換瀏覽器、開新帳號也換不到新的次數 */
    IP("ip"),
    /** 照帳號 */
    ACCOUNT("account"),
    /** 全站共用一份 */
    GLOBAL("global");

    private final String id;

    QuotaSubject(String id) {
        this.id = id;
    }

    public static QuotaSubject of(String id) {
        for (QuotaSubject subject : values()) {
            if (subject.id.equals(id)) {
                return subject;
            }
        }
        throw new IllegalArgumentException("不支援的計數方式：" + id);
    }
}
