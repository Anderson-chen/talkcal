package io.github.andersonchen.talkcal.auth.application.domain.model;

/**
 * 訪客、免費、付費三級。訪客也是一個帳號（按「以訪客身分試用」時開的），沒有帳號名稱和密碼，註冊時升級成免費。
 *
 * 資料庫存的是 id()，維護者手動改等級時寫的也是 id() 的值（見 V14）。label() 直接顯示在畫面上。
 */
public enum MembershipTier {
    GUEST("guest", "訪客"),
    FREE("free", "免費會員"),
    PAID("paid", "付費會員");

    private final String id;
    private final String label;

    MembershipTier(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static MembershipTier of(String id) {
        for (MembershipTier tier : values()) {
            if (tier.id.equals(id)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("不支援的會員等級：" + id);
    }
}
