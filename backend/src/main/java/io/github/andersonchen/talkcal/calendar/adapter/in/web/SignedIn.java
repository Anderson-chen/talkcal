package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.security.Principal;

/**
 * 登入者 → 擁有者（#104）：行事曆和 AI 助理兩個 controller 都要，放在自己的類別，不讓一個 controller 呼叫另一個。
 *
 * 登入者從 java.security.Principal 拿（Servlet 的標準型別，不是 Spring Security 的）：
 * calendar 不認得 auth 模組、也不必知道是怎麼登入的，只知道登入者的名字就是擁有者 ID（AccountPrincipals 保證）。
 */
final class SignedIn {

    private SignedIn() {
    }

    /**
     * SecurityFilterChain 已經擋掉沒登入的請求，走到這裡一定有登入者；
     * 萬一沒有（設定被改壞），丟 IllegalStateException 回 502，而不是 NullPointerException、或默默當成某個人。
     * 名字不是 UUID 也一樣：那是登入那一頭的錯，不是呼叫端送錯東西，不該變成 400。
     */
    static OwnerId owner(Principal signedIn) {
        if (signedIn == null) {
            throw new IllegalStateException("沒有登入者：/api/calendar 應該要先登入");
        }
        try {
            return OwnerId.of(signedIn.getName());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("登入者的名字不是帳號 ID：" + signedIn.getName(), e);
        }
    }
}
