package io.github.andersonchen.talkcal.auth.application.domain.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 使用者自己取的帳號名稱。登入時打的就是它。
 *
 * 規則：
 * - 一律存成小寫：「Amy」和「amy」是同一個帳號，不然兩個人可以註冊看起來一樣的名字，登入時也常因為大小寫打錯
 * - 3～32 個字：太短容易撞名、太長畫面放不下
 * - 只能用英文小寫、數字、底線、點、減號：不收空白和中文，避免看起來一樣、其實不同的字元（例如全形和半形）
 *   被拿來冒充別人
 */
public record Username(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[a-z0-9_.-]{3,32}");

    public Username {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("帳號名稱要 3～32 個字，只能用英文、數字、底線、點、減號");
        }
    }

    /** 使用者打的原樣：去掉頭尾空白、轉小寫之後再檢查規則。 */
    public static Username of(String text) {
        if (text == null) {
            throw new IllegalArgumentException("帳號名稱不可為 null");
        }
        return new Username(text.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
