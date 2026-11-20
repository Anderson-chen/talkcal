package io.github.andersonchen.talkcal.quota.application.port.in;

import java.util.List;
import java.util.Optional;

/**
 * 每個請求進到 controller 之前先算一次：沒有規則對到就不限制；對到了就在每一份計數上加一，
 * 任何一份超過上限就全部退回、丟 QuotaExceededException。呼叫端判斷這一次不該算（例如請求最後沒有成功）時，呼叫 refund 退回。
 *
 * 計數暫時讀不到（Redis 無法連線）時放行、回 empty：寧可那段時間多給幾次，也不要 Redis 一無法連線，整站就不能用。
 */
public interface ConsumeQuotaUseCase {

    Optional<Consumed> consume(QuotaRequest request);

    void refund(Consumed consumed);

    /**
     * pathPattern 是 controller 上的路徑樣板（見 QuotaRule）；tier 是請求者的等級，沒登入是 none；
     * client 是用來分 IP 的 key；account 是帳號 ID，沒登入是 null
     */
    record QuotaRequest(String method, String pathPattern, String tier, String client, String account) {
    }

    /** 這一次算在哪幾份計數上，退回時要用 */
    record Consumed(List<String> counterKeys) {

        public Consumed {
            counterKeys = List.copyOf(counterKeys);
        }
    }
}
