package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;

import java.util.List;

/**
 * 助理的提議卡片：發出去時記下來、被拒絕時核對後才算。
 *
 * 採納不在這裡：採納就是存進行事曆，由 AddEventsUseCase 帶著卡片 id 核對。
 * 拒絕要另外講：按「取消」只改前端的畫面，前端不另外告訴後端，後端就收不到。
 * 沒按加入也沒按取消（例如直接清空對話）的卡片兩邊都不算，那是沒表態，不是拒絕。
 */
public interface TrackProposalsUseCase {

    /**
     * 一則回覆的卡片不會到這麼多；上限是為了請求大小：核對是一句 SQL 帶著所有 id，不設的話一個請求就能塞上萬個參數。
     * 指標灌不灌得了不靠這個：每張卡片本來就只算一次。
     */
    int MAX_DISMISSED_PER_CALL = 100;

    /**
     * 每張發一個 id、記下來，照順序回傳。
     * 失敗時：count 小於 0 → IllegalArgumentException。記錄失敗不會讓這裡失敗（見 ProposalStorePort）。
     */
    List<ProposalId> proposalsShown(OwnerId owner, int count);

    /**
     * 只有 owner 自己的、還沒表態過的才算，每張最多算一次；別人的、沒發過的、已經表態過的都靜靜略過，
     * 不洩漏別人的卡片存不存在。
     * 失敗時：owner、proposals 是 null 或含 null、超過 MAX_DISMISSED_PER_CALL 張 → IllegalArgumentException。
     */
    void proposalsDismissed(OwnerId owner, List<ProposalId> proposals);
}
