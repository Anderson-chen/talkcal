package io.github.andersonchen.talkcal.auth.adapter.in.web;

import java.util.List;

/** 這次啟動開了的 Google、GitHub 登入：有設 client id 的才算（見 OAuth2Registrations），前端照這份清單畫按鈕。 */
public record LoginProviders(List<String> ids) {

    public LoginProviders {
        ids = List.copyOf(ids);
    }
}
