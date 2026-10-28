package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;

import java.util.List;

/**
 * 助理提議的卡片（inbound port，#36、#223）：發出去時記下來、被拒絕時核對後才算。
 *
 * 被採納的不在這裡：採納就是存進行事曆，由 AddEventsUseCase 帶著卡片的 id 一起核對。
 * 拒絕要另外講，是因為按「取消」只改前端的畫面、不經過任何存檔，後端不被告知就永遠不知道。
 * 沒按加入也沒按取消（例如直接清空對話）的卡片兩邊都不算 —— 那是「沒表態」，不是拒絕。
 */
public interface TrackProposalsUseCase {

    /**
     * 助理要給 owner 看 count 張卡片：每張發一個 id、記下來，照順序回傳。0 張就是空的。
     * 失敗時：count 小於 0 → IllegalArgumentException。記錄失敗不會讓這裡失敗（見 SaveProposalsPort）。
     */
    List<ProposalId> proposalsShown(OwnerId owner, int count);

    /**
     * owner 按掉了這幾張卡片。只有 owner 自己的、還沒表態過的才算，每張最多算一次；
     * 別人的、沒發過的、已經表態過的都靜靜略過，不報錯（不洩漏別人的卡片存不存在）。
     * 失敗時：owner、proposals 是 null 或含 null → IllegalArgumentException。
     */
    void proposalsDismissed(OwnerId owner, List<ProposalId> proposals);
}
