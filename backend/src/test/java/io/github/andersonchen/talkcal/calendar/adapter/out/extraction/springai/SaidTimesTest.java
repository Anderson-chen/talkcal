package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SaidTimes")
class SaidTimesTest {

    @Nested
    @DisplayName("endIsGrounded：結束時間的引用，要是這筆行程的「到 …」")
    class EndIsGrounded {

        @Test
        @DisplayName("開始引用 …… 到／至／~／- 結束引用，在同一個子句裡")
        void sameClauseRange() {
            assertTrue(SaidTimes.endIsGrounded("九點", "十點半", "下週三早上九點到十點半看牙醫"));
            assertTrue(SaidTimes.endIsGrounded("晚上十點", "凌晨一點", "晚上十點到凌晨一點唱歌"));
            assertTrue(SaidTimes.endIsGrounded("兩點", "三點半", "週四下午兩點至三點半交報告"));
            assertTrue(SaidTimes.endIsGrounded("14:00", "16:00", "週六 14:00-16:00 打球"));
            // 中間夾著要做的事也算同一個子句
            assertTrue(SaidTimes.endIsGrounded("三點", "五點", "明天三點開會到五點"));
            // 模型連「到」一起抄也認得
            assertTrue(SaidTimes.endIsGrounded("九點", "到十點半", "九點到十點半看牙醫"));
        }

        @Test
        @DisplayName("一句兩筆：拿了別筆的結束時間（隔著逗號）→ 不算")
        void endFromAnotherClause() {
            assertFalse(SaidTimes.endIsGrounded("三點", "十點半", "明天三點跟小明吃飯，下週三早上九點到十點半看牙醫"));
            assertTrue(SaidTimes.endIsGrounded("早上九點", "十點半", "明天三點跟小明吃飯，下週三早上九點到十點半看牙醫"));
        }

        @Test
        @DisplayName("回報的 bug：沒講結束時間，模型把開始時間抄成結束引用 → 不算")
        void startQuotedAsEnd() {
            assertFalse(SaidTimes.endIsGrounded("下午3點", "下午3點", "明天下午3點和 Amy 開會"));
        }

        @Test
        @DisplayName("開始引用對不上原句（模型把「3點」寫成「三點」）→ 退回只檢查「到 + 結束引用」")
        void looseRuleWhenStartQuoteIsOff() {
            assertTrue(SaidTimes.endIsGrounded("下午三點", "五點", "明天下午3點到五點開會"));
            assertFalse(SaidTimes.endIsGrounded("下午三點", "下午三點", "明天下午3點和 Amy 開會"));
        }

        @Test
        @DisplayName("沒有、空白、只有「到」、原句裡沒有、特殊字元：都不算")
        void rejectsNonsense() {
            assertFalse(SaidTimes.endIsGrounded("七點", "十點", "晚上七點在健身房上瑜珈課"));
            assertFalse(SaidTimes.endIsGrounded("三點", null, "明天下午3點開會"));
            assertFalse(SaidTimes.endIsGrounded("三點", "  ", "明天下午3點開會"));
            assertFalse(SaidTimes.endIsGrounded(null, "到", "明天下午3點到公司開會"));
            assertFalse(SaidTimes.endIsGrounded(null, ".*", "明天三點到五點"));
        }
    }

    @Nested
    @DisplayName("meansAfternoon：沒講上下午的 1～6 點")
    class MeansAfternoon {

        @Test
        @DisplayName("沒講時段、1～6 點 → 下午")
        void ambiguousEarlyHours() {
            assertTrue(SaidTimes.meansAfternoon("三點", 3, "明天三點開會到五點"));
            assertTrue(SaidTimes.meansAfternoon("五點", 5, "明天三點開會到五點"));
            assertTrue(SaidTimes.meansAfternoon("1點", 1, "明天1點開會"));
            assertTrue(SaidTimes.meansAfternoon("6點半", 6, "明天6點半吃飯"));
            assertTrue(SaidTimes.meansAfternoon("3:00", 3, "明天 3:00 開會"));
        }

        @Test
        @DisplayName("有講時段（早、午、晚、晨、夜、凌、AM/PM）→ 照字面")
        void statedPeriod() {
            for (String quote : new String[] {"早上三點", "上午五點", "凌晨兩點", "半夜三點", "清晨六點", "今晚六點", "3am", "3 PM"}) {
                assertFalse(SaidTimes.meansAfternoon(quote, 3, "明天" + quote + "出發"), quote);
            }
        }

        @Test
        @DisplayName("1～6 點以外不猜：七點、十二點、零點都照字面")
        void outsideRange() {
            assertFalse(SaidTimes.meansAfternoon("七點", 7, "明天七點吃早餐"));
            assertFalse(SaidTimes.meansAfternoon("十二點", 12, "明天十二點吃午餐"));
            assertFalse(SaidTimes.meansAfternoon("零點", 0, "明天零點跨年"));
        }

        @Test
        @DisplayName("補了 0 的 24 小時制（03:00）是刻意寫的 → 照字面")
        void zeroPadded24h() {
            assertFalse(SaidTimes.meansAfternoon("03:00", 3, "明天 03:00 看流星"));
        }

        @Test
        @DisplayName("引用對不上原句、或沒有引用 → 不當成證據，維持模型的解讀")
        void ungroundedQuote() {
            assertFalse(SaidTimes.meansAfternoon("三點", 3, "明天3點開會"));
            assertFalse(SaidTimes.meansAfternoon(null, 3, "明天三點開會"));
        }
    }
}
