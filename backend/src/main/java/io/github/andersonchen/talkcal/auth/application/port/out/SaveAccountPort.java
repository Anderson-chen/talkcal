package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 撞名、撞 identity 時回 false、不丟例外：那是正常會發生的事，不是故障。
 * 有沒有重複，要由資料庫的唯一約束判斷，不能先查再存：兩個請求同時進來，先查時兩邊都會看到沒有。
 */
public interface SaveAccountPort {

    boolean saveIfUsernameFree(Account account);

    /**
     * 存帳號，同時記下 identity 對到這個帳號，兩件事一起成功或一起不算。
     * identity 已經對到別的帳號（兩個分頁同時第一次登入，另一邊先存好）就回 false，帳號也不留下。
     */
    boolean saveIfIdentityFree(Account account, ExternalIdentity identity);

    /**
     * 帳號還是訪客，就補上帳號名稱和密碼、改成免費會員、刪掉它的訪客 token。
     * 回傳 USERNAME_TAKEN、NOT_A_GUEST 不丟例外，理由同上：撞名由資料庫的唯一約束判斷。
     */
    UpgradeResult upgradeGuest(AccountId guest, Account.PasswordLogin login);

    enum UpgradeResult { UPGRADED, USERNAME_TAKEN, NOT_A_GUEST }

    /**
     * upgraded.id() 這個帳號還是訪客，就改成 upgraded 的顯示名稱和等級、記下 identity 對到它、刪掉它的訪客 token，
     * 全部一起成功或一起不算。帳號已經不是訪客、或 identity 已經對到別的帳號時回 false，什麼都不改。
     */
    boolean upgradeGuestWithIdentity(Account upgraded, ExternalIdentity identity);
}
