package io.github.andersonchen.talkcal.calendar.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * 數出幾個時間點之後各有幾個擁有者有動作（outbound port，#36 的活躍使用者）。
 *
 * 「有動作」= 跟助理說過話，或存過行程。只看月曆不算：讀取沒有留下紀錄，要算就得另外記每次讀取，不划算。
 *
 * 為什麼從資料庫數、不在記憶體裡記一份「今天見過誰」：
 * 記憶體的版本重啟就歸零，開兩台就各數各的；資料庫裡本來就有每一筆的擁有者和時間，數出來的是事實。
 *
 * 一次收好幾個時間點：日活、週活、月活掃的是同一批資料，一句 SQL 一起數，不必掃三次。
 *
 * 回傳的順序跟 sinces 一樣。失敗時：資料庫出事 → IllegalStateException。
 */
public interface CountActiveOwnersPort {

    List<Integer> countActiveOwners(List<Instant> sinces);
}
