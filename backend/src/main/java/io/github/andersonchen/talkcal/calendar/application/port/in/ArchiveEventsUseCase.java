package io.github.andersonchen.talkcal.calendar.application.port.in;

import java.time.LocalDateTime;

/**
 * 結束時間早於 cutoff 的行程，壓縮後放進物件儲存，存好了才從行事曆刪掉。
 *
 * 排程的維護工作，不是誰的請求，所以不分擁有者。cutoff 由呼叫端算：保留多久是設定，不是業務規則。
 * 一個人、一個月一包；一包存不進去，只有那一包的行程留在資料庫，下次排程再試。
 */
public interface ArchiveEventsUseCase {

    Report archiveEndedBefore(LocalDateTime cutoff);

    /**
     * @param archived     存進歸檔、也從資料庫刪掉的筆數
     * @param failedGroups 存不進去的包數，那幾包的行程還在資料庫裡
     */
    record Report(int archived, int failedGroups) {
    }
}
