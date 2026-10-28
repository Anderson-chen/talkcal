package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 一筆行程是怎麼來的：使用者採納了 AI 助理的提議，還是自己手動填的。
 *
 * 只用在產品指標（#36）：「助理提議的行程被採納了幾成」「手動建 vs 助理建」要分得出來。
 * 不存進資料庫 —— 目前沒有任何畫面或規則需要知道一筆舊行程是誰建的，存了就多一個要維護的欄位。
 *
 * 由伺服器判斷，不是前端說了算（#223）：存檔時帶著的提議卡片 id 核對過（是自己的、還沒表態過）才算 ASSISTANT，其餘都是 MANUAL。
 *
 * 在助理的卡片上按「修改」、改完再存，還是算 ASSISTANT：起點是助理的提議，使用者只是調整細節，
 * 這正是「提議有用」的訊號；算成手動的話，助理的價值會被低估。
 */
public enum EventOrigin {
    ASSISTANT,
    MANUAL
}
