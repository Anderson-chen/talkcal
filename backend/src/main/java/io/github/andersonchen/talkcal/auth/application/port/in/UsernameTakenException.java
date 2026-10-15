package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

/**
 * 註冊時帳號名稱已經有人用了。HTTP 那層翻成 409。
 *
 * 代價：外人可以靠這個訊息試出「某個帳號名稱存不存在」。註冊流程本來就很難完全避免（總要告訴使用者換一個名字），
 * 所以靠 nginx 對註冊限流，讓大量試探變慢。
 */
public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(Username username) {
        super("帳號名稱「" + username + "」已經有人用了，換一個試試");
    }
}
