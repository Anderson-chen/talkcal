package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 存一個新帳號。
 *
 * 撞名、撞 identity 時回 false、不丟例外：那是正常會發生的事，不是故障。
 * 撞了沒有要由資料庫的唯一約束判斷，不能先查再存 —— 兩個請求同時進來，先查時兩邊都會看到「沒有」。
 */
public interface SaveAccountPort {

    /** 帳密註冊。帳號名稱已經有人用了就回 false。 */
    boolean saveIfUsernameFree(Account account);

    /**
     * 第一次用 Google、GitHub 登入：存帳號，同時記下 identity 對到它，兩件事一起成功或一起不算。
     * identity 已經對到別的帳號（例如兩個分頁同時第一次登入，另一邊先存好了）就回 false，帳號也不留下。
     */
    boolean saveIfIdentityFree(Account account, ExternalIdentity identity);
}
