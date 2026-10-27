package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 忘掉一個擁有者（inbound port）：他在行事曆裡留下的一切 —— 行程、跟 AI 助理的對話 —— 全部刪掉（#52）。
 *
 * 給「使用者要求刪除帳號」用。calendar 不知道帳號這回事（模組互不認識），只知道「這個擁有者不要了」；
 * 由 app.config 把 auth 的刪帳號流程接過來。
 *
 * 重複呼叫沒關係：已經沒有東西的擁有者，刪 0 筆、照樣成功。刪帳號時中途失敗，重試一次就好。
 * 回傳刪了幾列（行程和對話訊息加起來），給呼叫端記一筆「刪了多少」。
 *
 * 失敗時：owner 是 null → IllegalArgumentException；資料庫出事 → IllegalStateException（什麼都沒刪）。
 */
public interface ForgetOwnerUseCase {

    int forget(OwnerId owner);
}
