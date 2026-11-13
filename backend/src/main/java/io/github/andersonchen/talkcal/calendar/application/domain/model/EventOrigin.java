package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 使用者採納了 AI 助理的提議，還是自己手動填的。只用在產品指標（採納率、手動建和助理建的比例），不存進資料庫：
 * 沒有畫面或規則需要知道舊行程是誰建的，存了只是多一個要維護的欄位。
 *
 * 由伺服器判斷：存檔時帶來的提議卡片 id 核對過（是自己的、還沒表態過）才算 ASSISTANT，其餘都是 MANUAL。
 *
 * 卡片按「修改」、改完再存還是算 ASSISTANT：起點是助理的提議，使用者只是調整細節；
 * 算成手動的話，助理的價值會被低估。
 */
public enum EventOrigin {
    ASSISTANT,
    MANUAL
}
