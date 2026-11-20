package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.auth.application.port.in.FinishGuestMergesUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 每 5 分鐘把訪客合併之後還留在訪客名下的資料再搬一次（見 FinishGuestMergesUseCase）：
 * 合併之後最晚過 talkcal.guest.merge-finish-after 再加 5 分鐘，資料就在併進的那個帳號裡。
 */
public final class GuestMergeFollowUp {

    private static final Logger log = LoggerFactory.getLogger(GuestMergeFollowUp.class);

    private final FinishGuestMergesUseCase finishGuestMerges;

    public GuestMergeFollowUp(FinishGuestMergesUseCase finishGuestMerges) {
        this.finishGuestMerges = Objects.requireNonNull(finishGuestMerges, "finishGuestMerges 不可為 null");
    }

    // 多台同時執行也沒關係：同一筆合併搬兩次，後搬的那台只是搬 0 筆（歸檔那一段見 ArchiveBucket.moveAll）
    @Scheduled(cron = "0 */5 * * * *")
    void run() {
        int finished = finishGuestMerges.finishDue();
        // 大多數時候沒有要搬的，每 5 分鐘都記一行的話，log 裡大多是「0 筆」
        if (finished > 0) {
            log.info("訪客合併之後再搬一次：{} 筆", finished);
        }
    }
}
