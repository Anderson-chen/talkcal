package io.github.andersonchen.talkcal.auth.application.port.in;

/**
 * 刪掉太久沒用的訪客帳號，連同它的行程和對話。期限跟訪客 token 一樣：token 已經過期，沒有人能再登入那個帳號。
 * 回傳刪了幾個
 */
public interface ExpireGuestsUseCase {

    int expireInactive();
}
