package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import java.util.regex.Pattern;

/**
 * 拿使用者的原話核對模型對「幾點」的解讀。模型除了給 start、end，還要照抄原話裡講開始、結束時間的那幾個字
 * （startSaid、endSaid），這裡的規則只看這些引用和原句，結果確定、測得到。
 * 把規則寫進 prompt 的話，模型只照做一半；引用加程式核對才每次都對。
 *
 * 規則只適用中文（「到」「下午」），所以放在這個 adapter：換一家模型、換一種語言，這裡跟著換，domain 不動。
 */
final class SaidTimes {

    // 中文、全形、半形裡表示「到」的寫法：九點到十點半、兩點至三點、14:00-16:00、3～5 點
    private static final String RANGE_MARK = "(?:到|至|~|～|-|–|—)";
    private static final Pattern LEADING_RANGE_MARK = Pattern.compile("^" + RANGE_MARK + "\\s*");
    // 中間不能隔著逗號、句號這些：那通常是另一筆行程了
    private static final String SAME_CLAUSE = "[^，,。；;！!？?\\n]*?";

    // 早上、上午、中午、下午、晚上、傍晚、清晨、凌晨、半夜、深夜、今晚……都至少有其中一個字
    private static final Pattern DAY_PERIOD = Pattern.compile("[早晚午晨夜凌]|(?i:a\\.?m\\.?|p\\.?m\\.?)");
    // 「03:00」這種補了 0 的 24 小時制是寫的人有意這樣寫，不去猜
    private static final Pattern ZERO_PADDED_24H = Pattern.compile("(?<!\\d)0\\d\\s*[:：]");

    private SaidTimes() {
    }

    /**
     * 模型說使用者講了結束時間，這段引用（endSaid）真的是這筆行程的結束時間嗎？
     *
     * 沒講結束時間時，模型會給跟開始一樣的時間、或自己編一個（「明天下午3點和 Amy 開會」→ 15:00 或 18:00），
     * 不會照 prompt 給 null。只查引用在不在原句裡不夠，有兩種錯查不出來：
     * - 把開始時間（「下午3點」）抄成 endSaid：所以要求引用緊接在「到」後面
     * - 一句兩筆時拿了別筆的結束時間（「三點吃飯，九點到十點半看牙醫」的「十點半」）：
     *   所以要求「開始引用 …… 到 結束引用」在同一個子句裡
     *
     * 開始引用跟原句對不上時（模型會把「3點」寫成「三點」），只檢查「到 + 結束引用」。
     * 代價：用時長講結束（「開會兩小時」）會被當成沒講，交給 domain 補預設長度。寧可少採用，不要存錯。
     */
    static boolean endIsGrounded(String startSaid, String endSaid, String said) {
        String end = stripRangeMark(endSaid);
        if (end == null) {
            return false;
        }
        String toEnd = RANGE_MARK + "\\s*" + Pattern.quote(end);
        if (isQuoted(startSaid, said)) {
            return Pattern.compile(Pattern.quote(startSaid.strip()) + SAME_CLAUSE + toEnd).matcher(said).find();
        }
        return Pattern.compile(toEnd).matcher(said).find();
    }

    /**
     * 這段引用沒講上午下午、而且是 1～6 點的話，一律當成下午，這是產品規則。
     *
     * 「明天三點開會」說的幾乎都是下午三點，模型卻照字面給 03:00。
     * 只對 1～6 點這樣做：七點以後早上晚上都常見，猜錯的代價比不猜大；十二點本來就是中午。
     * 引用要在原句裡才算數：對不上的引用不能當證據，維持模型原本的解讀。
     */
    static boolean meansAfternoon(String quote, int hour, String said) {
        if (hour < 1 || hour > 6 || !isQuoted(quote, said)) {
            return false;
        }
        return !DAY_PERIOD.matcher(quote).find() && !ZERO_PADDED_24H.matcher(quote).find();
    }

    private static boolean isQuoted(String quote, String said) {
        return quote != null && !quote.isBlank() && said.contains(quote.strip());
    }

    // 模型有時連「到」一起抄（「到十點半」），先拿掉；拿掉之後是空的就等於沒引用
    private static String stripRangeMark(String quote) {
        if (quote == null || quote.isBlank()) {
            return null;
        }
        String stripped = LEADING_RANGE_MARK.matcher(quote.strip()).replaceFirst("");
        return stripped.isEmpty() ? null : stripped;
    }
}
