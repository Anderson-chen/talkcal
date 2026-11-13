package io.github.andersonchen.talkcal.calendar.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * 幾個時間點之後各有幾個擁有者跟助理說過話、或存過行程。只看月曆不算：讀取沒有留下紀錄，要算就得另外記每次讀取。
 *
 * 從資料庫數，不在記憶體記「今天見過誰」：記憶體的版本重啟就歸零、開兩台就各數各的；
 * 資料庫裡本來就有每一筆的擁有者和時間。
 *
 * 一次收好幾個時間點：日活、週活、月活掃的是同一批資料，一句 SQL 一起數。
 *
 * 回傳的順序跟 sinces 一樣。失敗時：資料庫出事 → IllegalStateException。
 */
public interface CountActiveOwnersPort {

    List<Integer> countActiveOwners(List<Instant> sinces);
}
