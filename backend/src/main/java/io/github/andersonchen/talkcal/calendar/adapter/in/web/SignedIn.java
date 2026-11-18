package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.security.Principal;

/**
 * 從登入者取出擁有者。行事曆和 AI 助理兩個 controller 都用，放在自己的類別，不讓一個 controller 呼叫另一個。
 *
 * 登入者從 java.security.Principal 取（Servlet 的標準型別）：calendar 不 import auth 模組和 Spring Security，
 * 只依賴登入者的名字就是帳號 ID 這件事（見 AccountPrincipals）。
 */
final class SignedIn {

    private SignedIn() {
    }

    /**
     * 只在要登入的端點呼叫：SecurityFilterChain 已經擋掉沒登入的請求。POST /api/calendar/assistant 開放給訪客，
     * 要先判斷有沒有登入者再呼叫，見 CalendarAssistantController。萬一沒有登入者（安全設定改錯了），丟 IllegalStateException 回 502，
     * 不丟 NullPointerException，也不在沒有登入者時當成某個人。名字不是 UUID 也一樣：那是登入那一端的錯，不是呼叫端送錯，不該變成 400。
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
