package io.github.andersonchen.talkcal.calendar.application.port.in;

/** 訪客做了要登入才能做的事，例如送固定問題以外的話。在 HTTP 對應到 401，跟沒登入呼叫其他 API 一樣 */
public final class SignInRequiredException extends RuntimeException {

    public SignInRequiredException() {
        super("請先登入");
    }
}
