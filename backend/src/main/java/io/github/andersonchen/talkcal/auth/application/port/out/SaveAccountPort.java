package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 撞名、撞 identity 時回 false、不丟例外：那是正常會發生的事，不是故障。
 * 撞了沒有要由資料庫的唯一約束判斷，不能先查再存：兩個請求同時進來，先查時兩邊都會看到沒有。
 */
public interface SaveAccountPort {

    boolean saveIfUsernameFree(Account account);

    /**
     * 存帳號，同時記下 identity 對到這個帳號，兩件事一起成功或一起不算。
     * identity 已經對到別的帳號（兩個分頁同時第一次登入，另一邊先存好）就回 false，帳號也不留下。
     */
    boolean saveIfIdentityFree(Account account, ExternalIdentity identity);
}
