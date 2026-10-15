package io.github.andersonchen.talkcal.auth.adapter.in.web;

import java.util.List;

/**
 * 除了帳密之外，這次啟動開了哪些登入方式（例如 ["google", "github"]）。
 * 設定了 client id 的才算（見 app.config 的 OAuth2Registrations），前端照這份清單決定畫哪些按鈕。
 */
public record LoginProviders(List<String> ids) {

    public LoginProviders {
        ids = List.copyOf(ids);
    }
}
