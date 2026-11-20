package io.github.andersonchen.talkcal.auth.application.port.in;

/**
 * 刪掉太久沒用的訪客帳號，連同帳號的行程和對話，回傳刪了幾個。
 * 期限跟訪客 token 一樣：每次使用（用 token 回來、session 還在時的 GuestAccessUseCase.keepActive）都延長 token，
 * 超過期限沒用的訪客，token 已經過期、session 也早就過期，沒有人能再登入那個帳號。
 */
public interface ExpireGuestsUseCase {

    int expireInactive();
}
