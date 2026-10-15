package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;

/**
 * 存一個新帳號。
 *
 * 帳號名稱撞名時回 false、不丟例外：「已經有人用了」是正常會發生的事，不是故障。
 * 撞名要由資料庫的唯一約束判斷，不能先查再存 —— 兩個人同時註冊同一個名字，先查時兩邊都會看到「沒人用」。
 */
public interface SaveAccountPort {

    boolean saveIfUsernameFree(Account account);
}
