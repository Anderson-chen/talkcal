package io.github.andersonchen.talkcal.auth.application.domain.model;

/**
 * 「某個 provider 的某個使用者」，例如「Google 的 sub=1234」。用它找出對應到我們的哪個帳號。
 *
 * subject 用 provider 發的、不會變的使用者 ID（Google 的 sub、GitHub 的數字 id），
 * 不用 email 或 GitHub 帳號名稱：那兩個使用者自己改得掉，改了就會對到另一個帳號（見 V10 的 migration）。
 */
public record ExternalIdentity(Provider provider, String subject) {

    /** 支援的 provider。資料庫存的是 id()，跟 Spring Security 設定裡的 registrationId 同一個字。 */
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

        /** 不認得的 provider 丟 IllegalArgumentException：設定裡多了一個沒寫對應方式的 provider，就該在第一次登入時出錯 */
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
