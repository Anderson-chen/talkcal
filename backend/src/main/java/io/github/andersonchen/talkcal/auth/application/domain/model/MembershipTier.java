package io.github.andersonchen.talkcal.auth.application.domain.model;

/**
 * 有帳號的人分免費、付費兩級；沒登入的訪客沒有帳號，不在這裡。
 *
 * 資料庫存的是 id()，維護者手動改等級時寫的也是它（見 V14）。
 */
public enum MembershipTier {
    FREE("free"),
    PAID("paid");

    private final String id;

    MembershipTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
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
