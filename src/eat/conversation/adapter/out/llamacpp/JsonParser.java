package eat.conversation.adapter.out.llamacpp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 手寫的最小 JSON 剖析器：把 JSON 文字讀成 Java 物件。
 *
 * 為什麼跟 {@link Json} 分開成兩個 class：
 * Json 是無狀態的轉換（同樣的輸入永遠同樣的輸出），JsonParser 是有狀態的掃描
 * （它要記住「讀到第幾個字元」）。機制不同，所以不混在一起。
 *
 * 為什麼不用 indexOf 抽欄位就好：
 * 1. 回應裡同時有 content 和 reasoning_content，欄位順序不保證；
 * 2. 模型回覆的內容本身就可能包含 "content": 這串字；
 * 3. 內容裡的跳脫引號不能當字串結尾。
 * 要正確處理這三件事，需要真的逐字元掃描，搜尋子字串做不到。
 *
 * 範圍刻意收在「llama.cpp 實際會回的 JSON」：
 * 不支援 NaN / Infinity 這類非標準擴充，也沒有巢狀深度上限
 * —— 來源是自己機器上的 server。哪天要接不可信的遠端來源，深度上限要補上。
 */
final class JsonParser {

    private final String source;
    // 掃描位置：這就是「有狀態」的部分，也是它必須是實例而不是靜態工具的原因
    private int position;

    private JsonParser(String source) {
        this.source = source;
    }

    /**
     * 把 JSON 文字讀成：
     * 物件 -> Map（保留欄位順序，好除錯）、陣列 -> List、
     * 字串 -> String、數字 -> Double、true/false -> Boolean、null -> null。
     */
    static Object parse(String json) {
        Objects.requireNonNull(json, "json 不可為 null");

        JsonParser parser = new JsonParser(json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        // 讀完一個值之後還有東西，代表這不是一份完整的 JSON。
        // 安靜忽略尾巴會讓「回應被截斷」這種問題偽裝成正常結果，所以要炸
        if (parser.position < json.length()) {
            throw parser.fail("JSON 結束後還有多餘內容");
        }
        return value;
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return members;
        }
        while (true) {
            skipWhitespace();
            // 重複的 key 由後者覆蓋，跟主流剖析器一致
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(key, readValue());
            skipWhitespace();
            char next = take();
            if (next == '}') {
                return members;
            }
            if (next != ',') {
                throw fail("物件的成員之間預期是 , 或 }");
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return items;
        }
        while (true) {
            skipWhitespace();
            items.add(readValue());
            skipWhitespace();
            char next = take();
            if (next == ']') {
                return items;
            }
            if (next != ',') {
                throw fail("陣列的元素之間預期是 , 或 ]");
            }
        }
    }

    /**
     * 讀一個字串，並把跳脫還原。
     * 這是整個剖析器最關鍵的地方：只有逐字元判斷「這個引號前面是不是跳脫」，
     * 才能正確找到字串真正的結尾
     */
    private String readString() {
        expect('"');
        StringBuilder text = new StringBuilder();
        while (true) {
            if (position >= source.length()) {
                throw fail("字串沒有結尾的引號");
            }
            char c = source.charAt(position++);
            if (c == '"') {
                return text.toString();
            }
            if (c != '\\') {
                text.append(c);
                continue;
            }
            // 走到這裡代表遇到跳脫字元，下一個字元決定它還原成什麼
            if (position >= source.length()) {
                throw fail("跳脫字元後面沒有內容");
            }
            char escaped = source.charAt(position++);
            switch (escaped) {
                case '"' -> text.append('"');
                case '\\' -> text.append('\\');
                // JSON 允許跳脫斜線（我們自己不會產生，但別人的回應可能有）
                case '/' -> text.append('/');
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> text.append(readUnicodeEscape());
                default -> throw fail("不認得的跳脫：反斜線加上 " + escaped);
            }
        }
    }

    /**
     * 讀四位十六進位並還原成字元。
     * 直接 append 成 char 就自然支援代理對（emoji 會是連續兩個這種跳脫，
     * 兩個 char 拼起來剛好是 Java 字串裡的一個 emoji），不需要特別處理
     */
    private char readUnicodeEscape() {
        if (position + 4 > source.length()) {
            throw fail("unicode 跳脫不足四位");
        }
        String hex = source.substring(position, position + 4);
        try {
            char c = (char) Integer.parseInt(hex, 16);
            position += 4;
            return c;
        } catch (NumberFormatException e) {
            throw fail("unicode 跳脫不是合法的十六進位：" + hex);
        }
    }

    private Double readNumber() {
        int start = position;
        while (position < source.length() && isNumberChar(source.charAt(position))) {
            position++;
        }
        String raw = source.substring(start, position);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            // 空字串也會走到這裡 —— 代表這個位置根本不是任何合法的值
            throw fail("不是合法的值：" + (raw.isEmpty() ? String.valueOf(peek()) : raw));
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, position)) {
            throw fail("預期是 " + literal);
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        if (position >= source.length()) {
            throw fail("JSON 意外結束");
        }
        return source.charAt(position);
    }

    private char take() {
        char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        if (take() != expected) {
            // take() 已經前進了，回退一格讓錯誤訊息指向真正出錯的位置
            position--;
            throw fail("預期是 " + expected);
        }
    }

    /**
     * 錯誤訊息一律帶上位置。
     * 手寫剖析器最痛的就是「JSON 壞了」但不知道壞在哪，位置是最便宜的線索
     */
    private IllegalArgumentException fail(String reason) {
        return new IllegalArgumentException(reason + "（位置 " + position + "）");
    }
}
