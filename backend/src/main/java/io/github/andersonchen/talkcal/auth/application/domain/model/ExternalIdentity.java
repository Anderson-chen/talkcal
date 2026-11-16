package io.github.andersonchen.talkcal.auth.application.domain.model;

/**
 * 某個 provider 的某個使用者，例如 Google 的 sub=1234，用來找對應的帳號。
 *
 * subject 是 provider 發的、不會變的使用者 ID（Google 的 sub、GitHub 的數字 id）。不用 email 或 GitHub 帳號名稱：
 * 使用者自己改得掉，改了就對到另一個帳號（見 V10）。
 */
public record ExternalIdentity(Provider provider, String subject) {

    /** 資料庫存的是 id()，跟 Spring Security 設定裡的 registrationId 是同一個字。 */
    public enum Provider {
        GOOGLE("google"),
        GITHUB("github");

        private final String id;

        Provider(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 設定裡多了一個這裡沒有的 provider，第一次登入時就丟 IllegalArgumentException。 */
        public static Provider of(String id) {
            for (Provider provider : values()) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
            throw new IllegalArgumentException("不支援的登入方式：" + id);
        }
    }

    public ExternalIdentity {
        if (provider == null) {
            throw new IllegalArgumentException("provider 不可為 null");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(provider.id() + " 沒有給使用者 ID");
        }
    }

    @Override
    public String toString() {
        return provider.id() + ":" + subject;
    }
}
