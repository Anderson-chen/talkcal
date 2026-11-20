package io.github.andersonchen.talkcal.auth.application.port.in;

/**
 * 訪客合併（MergeGuestUseCase）之後才存進訪客名下的資料，再搬到併進的那個帳號。
 *
 * 合併的當下，訪客可能還有請求在處理中（例如助理還在回覆），存的時候用的還是訪客的 ID；
 * 歸檔的排程在合併之前讀了訪客的資料、合併之後才存好的，也一樣留在訪客名下。
 * 訪客帳號已經刪掉，不再搬一次的話，使用者看不到這些資料，清理的排程也刪不到。
 * 合併過了 talkcal.guest.merge-finish-after，這些請求都已經結束，才再搬一次。
 */
public interface FinishGuestMergesUseCase {

    /**
     * 合併超過那段時間的，把訪客名下剩下的全部搬過去、刪掉合併紀錄，回傳處理了幾筆合併。
     * 一筆失敗不擋後面的：全部試過一次才丟 IllegalStateException，失敗的紀錄留著，下一次再搬。
     */
    int finishDue();
}
