package io.github.andersonchen.talkcal.calendar.application.port.in;

import java.time.LocalDateTime;

/**
 * 把舊行程搬到歸檔（inbound port，#54）：結束時間早於 cutoff 的，壓縮後放進物件儲存，存好了才從行事曆刪掉。
 *
 * 由排程每天呼叫一次（app.job.EventArchiveJob），不是使用者的請求，所以不分擁有者。
 * cutoff 由呼叫端算（「現在」減掉保留期限）：保留多久是設定，不是業務規則。
 *
 * 一個人、一個月一包：其中一包存不進去，只有那一包的行程留在資料庫裡，其他包照常搬。
 * 失敗的那包下次排程再試 —— 留在資料庫裡就是最安全的狀態。
 */
public interface ArchiveEventsUseCase {

    Report archiveEndedBefore(LocalDateTime cutoff);

    /**
     * 這一輪的結果。
     *
     * @param archived     存進歸檔、也從資料庫刪掉的筆數
     * @param failedGroups 存不進去的包數（那幾包的行程還在資料庫裡）
     */
    record Report(int archived, int failedGroups) {
    }
}
