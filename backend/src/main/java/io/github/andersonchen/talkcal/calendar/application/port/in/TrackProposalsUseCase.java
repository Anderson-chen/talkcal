package io.github.andersonchen.talkcal.calendar.application.port.in;

/**
 * 助理提議的卡片後來怎麼了（inbound port，#36）：給人看了幾張、被拒絕了幾張。
 *
 * 被採納的不在這裡：採納就是存進行事曆，由 AddEventsUseCase 帶著 EventOrigin.ASSISTANT 記。
 * 拒絕要另外講，是因為按「取消」只改前端的畫面、不經過任何存檔，後端不被告知就永遠不知道。
 * 沒按加入也沒按取消（例如直接清空對話）的卡片兩邊都不算 —— 那是「沒表態」，不是拒絕。
 *
 * 失敗時：count 小於 0、或拒絕數超過 MAX_DISMISSED_PER_CALL → IllegalArgumentException。0 不算錯，什麼都不記。
 */
public interface TrackProposalsUseCase {

    /**
     * 拒絕一次最多回報幾張。一則回覆的卡片不會到這麼多；設上限是因為拒絕數由前端回報，
     * 不擋的話一個請求就能灌進任意大的數字，採納率就被搞壞了。
     * 給人看的張數沒有上限：那是伺服器自己數的，不經過前端。
     */
    int MAX_DISMISSED_PER_CALL = 20;

    void proposalsShown(int count);

    void proposalsDismissed(int count);
}
