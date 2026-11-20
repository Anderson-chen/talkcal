package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.OwnedDataStorePort;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OwnedDataService")
class OwnedDataServiceTest {

    private static final OwnerId GUEST = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000a");
    private static final OwnerId AMY = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000b");

    /** 資料庫和歸檔共用一份紀錄，看得出先後 */
    private final List<String> calls = new ArrayList<>();

    private final OwnedDataStorePort store = new OwnedDataStorePort() {
        @Override
        public void transfer(OwnerId from, OwnerId to) {
            calls.add("資料庫 transfer");
        }

        @Override
        public void deleteAll(OwnerId owner) {
            calls.add("資料庫 deleteAll");
        }
    };

    private final EventArchivePort archive = new EventArchivePort() {
        @Override
        public void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteAll(OwnerId owner) {
            calls.add("歸檔 deleteAll");
            return 0;
        }

        @Override
        public void transfer(OwnerId from, OwnerId to) {
            calls.add("歸檔 transfer");
        }
    };

    private final OwnedDataService service = new OwnedDataService(store, archive);

    @Test
    @DisplayName("搬移、刪除都包括行程的歸檔，先改資料庫再改歸檔")
    void archivesFollowTheDatabase() {
        service.transfer(GUEST, AMY);
        service.deleteAll(GUEST);

        assertEquals(List.of("資料庫 transfer", "歸檔 transfer", "資料庫 deleteAll", "歸檔 deleteAll"), calls);
    }

    @Test
    @DisplayName("反例：資料庫那一步失敗時不動歸檔，例外照樣丟出去")
    void databaseFailureStopsBeforeArchives() {
        OwnedDataService broken = new OwnedDataService(new OwnedDataStorePort() {
            @Override
            public void transfer(OwnerId from, OwnerId to) {
                throw new IllegalStateException("模擬：資料庫無法連線");
            }

            @Override
            public void deleteAll(OwnerId owner) {
                throw new IllegalStateException("模擬：資料庫無法連線");
            }
        }, archive);

        assertThrows(IllegalStateException.class, () -> broken.transfer(GUEST, AMY));
        assertThrows(IllegalStateException.class, () -> broken.deleteAll(GUEST));
        assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("反例：搬給自己什麼都不做；擁有者是 null 丟 IllegalArgumentException")
    void rejectsNothingToDo() {
        service.transfer(GUEST, GUEST);
        assertTrue(calls.isEmpty());

        assertThrows(IllegalArgumentException.class, () -> service.transfer(null, AMY));
        assertThrows(IllegalArgumentException.class, () -> service.deleteAll(null));
    }
}
