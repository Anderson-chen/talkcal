package adapter.out.llamacpp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsonParser")
class JsonParserTest {

    @Nested
    @DisplayName("讀取基本型別")
    class Values {

        @Test
        @DisplayName("字串")
        void readsString() {
            assertEquals("hello", JsonParser.parse("\"hello\""));
        }

        @Test
        @DisplayName("數字")
        void readsNumber() {
            assertEquals(42.0, JsonParser.parse("42"));
            assertEquals(-1.5, JsonParser.parse("-1.5"));
            assertEquals(1.0e3, JsonParser.parse("1e3"));
        }

        @Test
        @DisplayName("true / false / null")
        void readsLiterals() {
            assertEquals(Boolean.TRUE, JsonParser.parse("true"));
            assertEquals(Boolean.FALSE, JsonParser.parse("false"));
            assertNull(JsonParser.parse("null"));
        }

        @Test
        @DisplayName("前後的空白被忽略")
        void skipsSurroundingWhitespace() {
            assertEquals("hi", JsonParser.parse("  \n \"hi\" \t "));
        }
    }

    @Nested
    @DisplayName("讀取物件與陣列")
    class Structures {

        @Test
        @DisplayName("物件保留欄位順序")
        void keepsMemberOrder() {
            Object parsed = JsonParser.parse("{\"b\":1,\"a\":2}");

            assertEquals(List.of("b", "a"), List.copyOf(((Map<?, ?>) parsed).keySet()));
        }

        @Test
        @DisplayName("空物件與空陣列")
        void readsEmptyStructures() {
            assertEquals(Map.of(), JsonParser.parse("{}"));
            assertEquals(List.of(), JsonParser.parse("[]"));
        }

        @Test
        @DisplayName("巢狀結構")
        void readsNested() {
            Object parsed = JsonParser.parse("{\"a\":[{\"b\":\"c\"}]}");

            Map<?, ?> root = (Map<?, ?>) parsed;
            List<?> array = (List<?>) root.get("a");
            assertEquals("c", ((Map<?, ?>) array.get(0)).get("b"));
        }

        @Test
        @DisplayName("結構之間的空白被忽略")
        void skipsWhitespaceInsideStructures() {
            Object parsed = JsonParser.parse("{ \"a\" : [ 1 , 2 ] }");

            assertEquals(List.of(1.0, 2.0), ((Map<?, ?>) parsed).get("a"));
        }
    }

    @Nested
    @DisplayName("還原跳脫")
    class Unescaping {

        @Test
        @DisplayName("跳脫的引號不會被當成字串結尾")
        void quoteDoesNotEndString() {
            assertEquals("he said \"hi\"", JsonParser.parse("\"he said \\\"hi\\\"\""));
        }

        @Test
        @DisplayName("反斜線還原成單一個")
        void restoresBackslash() {
            assertEquals("C:\\new", JsonParser.parse("\"C:\\\\new\""));
        }

        @Test
        @DisplayName("換行、歸位、Tab 的短形式")
        void restoresCommonControlChars() {
            assertEquals("a\nb\rc\td", JsonParser.parse("\"a\\nb\\rc\\td\""));
        }

        @Test
        @DisplayName("四位十六進位跳脫")
        void restoresUnicodeEscape() {
            assertEquals("\u0001", JsonParser.parse("\"\\u0001\""));
            assertEquals("好", JsonParser.parse("\"\\u597d\""));
        }

        @Test
        @DisplayName("連續兩個跳脫組成代理對，還原成一個 emoji")
        void restoresSurrogatePair() {
            // llama.cpp 的回覆常常有 emoji，這題確認不會拆壞
            assertEquals("😊", JsonParser.parse("\"\\ud83d\\ude0a\""));
        }

        @Test
        @DisplayName("跳脫的斜線還原成斜線")
        void restoresSolidus() {
            assertEquals("a/b", JsonParser.parse("\"a\\/b\""));
        }

        @Test
        @DisplayName("跟 Json.string 互為反向")
        void roundTripsWithJsonString() {
            String original = "引號\" 反斜線\\ 換行\n 中文 emoji😊 控制字元\u0001";

            assertEquals(original, JsonParser.parse(Json.string(original)));
        }
    }

    @Nested
    @DisplayName("真實回應的陷阱")
    class RealWorldPitfalls {

        @Test
        @DisplayName("內容本身含有 content 欄位字樣時，不會抽錯欄位")
        void isNotFooledByContentLookalikeInsideText() {
            // 問模型「JSON 的 content 欄位怎麼寫」就會出現這種回應。
            // 天真的 indexOf 會在 reasoning_content 的內容裡先命中假的那個
            String json = "{\"reasoning_content\":\"要寫成 \\\"content\\\": 這樣\",\"content\":\"真正的答案\"}";

            assertEquals("真正的答案", ((Map<?, ?>) JsonParser.parse(json)).get("content"));
        }

        @Test
        @DisplayName("欄位順序調換也讀得對")
        void isNotOrderDependent() {
            String contentFirst = "{\"content\":\"答案\",\"reasoning_content\":\"想法\"}";
            String reasoningFirst = "{\"reasoning_content\":\"想法\",\"content\":\"答案\"}";

            assertEquals("答案", ((Map<?, ?>) JsonParser.parse(contentFirst)).get("content"));
            assertEquals("答案", ((Map<?, ?>) JsonParser.parse(reasoningFirst)).get("content"));
        }
    }

    @Nested
    @DisplayName("壞掉的輸入要當場炸")
    class BadInput {

        @Test
        @DisplayName("被截斷的回應不會被當成正常結果")
        void rejectsTruncatedJson() {
            assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":"));
            assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("\"no end"));
        }

        @Test
        @DisplayName("JSON 結束後還有多餘內容")
        void rejectsTrailingContent() {
            assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{} 垃圾"));
        }

        @Test
        @DisplayName("不認得的跳脫")
        void rejectsUnknownEscape() {
            assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("\"\\x\""));
        }

        @Test
        @DisplayName("錯誤訊息帶上位置，方便除錯")
        void reportsPosition() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> JsonParser.parse("{\"a\":1 \"b\":2}"));

            assertTrue(thrown.getMessage().contains("位置"), "訊息應該帶位置：" + thrown.getMessage());
        }

        @Test
        @DisplayName("null 輸入")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> JsonParser.parse(null));
        }
    }
}
