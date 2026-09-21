package eat.conversation.adapter.out.llamacpp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Json")
class JsonTest {

    @Nested
    @DisplayName("轉成 JSON 字串字面值")
    class ToStringLiteral {

        @Test
        @DisplayName("一般文字前後補上雙引號")
        void wrapsPlainTextInQuotes() {
            assertEquals("\"hello\"", Json.string("hello"));
        }

        @Test
        @DisplayName("空字串是合法的，結果是一對引號")
        void allowsEmptyText() {
            // Conversation 已經擋掉空白提問，Json 不該替 model 重複把關
            assertEquals("\"\"", Json.string(""));
        }

        @Test
        @DisplayName("雙引號被跳脫，才不會提前關閉字串")
        void escapesQuote() {
            assertEquals("\"he said \\\"hi\\\"\"", Json.string("he said \"hi\""));
        }

        @Test
        @DisplayName("反斜線被跳脫，路徑不會被讀成控制字元")
        void escapesBackslash() {
            assertEquals("\"C:\\\\new\"", Json.string("C:\\new"));
        }

        @Test
        @DisplayName("跳脫反斜線時不會連帶把後面的引號多跳脫一次")
        void doesNotDoubleEscape() {
            // 專抓 replace() 串接的順序 bug：先換引號再換反斜線會多出一層
            assertEquals("\"\\\\\\\"\"", Json.string("\\\""));
        }

        @Test
        @DisplayName("換行、歸位、Tab 用短形式")
        void escapesCommonControlChars() {
            assertEquals("\"a\\nb\\rc\\td\"", Json.string("a\nb\rc\td"));
        }

        @Test
        @DisplayName("沒有短形式的控制字元轉成四位十六進位跳脫")
        void escapesOtherControlCharsAsUnicode() {
            assertEquals("\"\\u0001\"", Json.string("\u0001"));
        }

        @Test
        @DisplayName("中文原樣輸出，不轉成跳脫形式")
        void keepsNonAsciiAsIs() {
            assertEquals("\"你好\"", Json.string("你好"));
        }

        @Test
        @DisplayName("null 是程式錯誤，當場丟例外而不是產生 null 字面值")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> Json.string(null));
        }
    }
}
