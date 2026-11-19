package io.github.andersonchen.talkcal.quota.application.port.in;

import java.util.Optional;

/**
 * 每個請求進到 controller 之前先算一次：沒有規則對到就不限制；對到了就加一，超過上限丟 QuotaExceededException。
 * 請求最後失敗在伺服器這邊（例如模型出錯）時呼叫 refund 退回這一次。
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

    /** 這一次算在哪一份計數上，退回時要用 */
    record Consumed(String counterKey) {
    }
}
