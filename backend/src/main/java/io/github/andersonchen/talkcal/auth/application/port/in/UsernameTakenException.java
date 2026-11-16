package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

/**
 * 在 HTTP 對應到 409。
 *
 * 代價：外人可以靠這個錯誤試出某個帳號名稱存不存在。註冊總要告訴使用者換一個名字，很難完全避免，
 * 所以靠 nginx 對註冊限流，讓大量試探變慢。
 */
public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(Username username) {
        super("帳號名稱「" + username + "」已經有人用了，換一個試試");
    }
}
