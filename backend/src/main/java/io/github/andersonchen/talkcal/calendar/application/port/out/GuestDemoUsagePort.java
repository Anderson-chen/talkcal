package io.github.andersonchen.talkcal.calendar.application.port.out;

import java.time.LocalDate;
import java.util.OptionalInt;

/**
 * 訪客每天看了幾次示範回答。
 *
 * 實作要讓好幾個 app 副本看到同一份計數，而且加一和檢查上限是同一個動作：同一個來源同時送好幾個請求，
 * 也只有前 limit 個成功。
 */
public interface GuestDemoUsagePort {

    /** 還沒到 limit 就加一，回傳加完的次數；已經到了不加，回 empty */
    OptionalInt use(String client, LocalDate day, int limit);
}
