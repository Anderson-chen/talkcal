package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EmbeddingResponse")
class EmbeddingResponseTest {

    @Nested
    @DisplayName("讀得出向量")
    class Success {

        @Test
        @DisplayName("取 data[0].embedding")
        void readsEmbedding() {
            String json = """
                    {"object":"list","model":"bge-m3","data":[
                      {"object":"embedding","index":0,"embedding":[0.5,-0.25,0.125]}]}""";

            assertArrayEquals(new float[] {0.5f, -0.25f, 0.125f},
                    EmbeddingResponse.vector(json), 0f);
        }

        @Test
        @DisplayName("整數也收：JSON 不分整數浮點，1 和 1.0 是同一回事")
        void readsIntegersAsFloats() {
            String json = """
                    {"data":[{"embedding":[1,0,-1]}]}""";

            assertArrayEquals(new float[] {1f, 0f, -1f}, EmbeddingResponse.vector(json), 0f);
        }

        @Test
        @DisplayName("多餘的欄位一律無視：協定加欄位不該讓我們壞掉")
        void ignoresUnknownFields() {
            String json = """
                    {"data":[{"embedding":[1],"未來才有的欄位":123}],"usage":{"prompt_tokens":4}}""";

            assertArrayEquals(new float[] {1f}, EmbeddingResponse.vector(json), 0f);
        }
    }

    @Nested
    @DisplayName("讀不出來的時候要說清楚是哪裡不對")
    class Failure {

        @Test
        @DisplayName("server 回報錯誤時，把它自己的訊息原封帶出去")
        void surfacesServerError() {
            String json = """
                    {"error":{"code":500,"message":"model does not support embeddings","type":"server_error"}}""";

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> EmbeddingResponse.vector(json));

            assertTrue(thrown.getMessage().contains("model does not support embeddings"),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("巢狀陣列：認出「打錯端點」這個常見情況並直說")
        void detectsNativeEndpointShape() {
            // llama.cpp 原生的 /embedding 回的是每個 token 一列，
            // 形狀跟 /v1/embeddings 不同。錯誤訊息要把人指到 baseUri／路徑那邊去查
            String json = """
                    {"data":[{"embedding":[[0.1,0.2],[0.3,0.4]]}]}""";

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> EmbeddingResponse.vector(json));

            assertTrue(thrown.getMessage().contains("/v1/embeddings"), thrown.getMessage());
        }

        @Test
        @DisplayName("不是合法 JSON 時用 IllegalArgumentException，跟「讀得懂但內容不對」分開")
        void rejectsMalformedJson() {
            assertThrows(IllegalArgumentException.class, () -> EmbeddingResponse.vector("{不是 JSON"));
        }

        @Test
        @DisplayName("一個完整的值後面還有東西：不安靜地只讀前半段")
        void rejectsTrailingTokens() {
            assertThrows(IllegalArgumentException.class,
                    () -> EmbeddingResponse.vector("{\"data\":[{\"embedding\":[1]}]} 多出來的東西"));
        }

        @Test
        @DisplayName("沒有 data 陣列")
        void rejectsMissingData() {
            assertThrows(IllegalStateException.class, () -> EmbeddingResponse.vector("{\"object\":\"list\"}"));
        }

        @Test
        @DisplayName("data 是空的")
        void rejectsEmptyData() {
            assertThrows(IllegalStateException.class, () -> EmbeddingResponse.vector("{\"data\":[]}"));
        }

        @Test
        @DisplayName("embedding 不是陣列")
        void rejectsNonArrayEmbedding() {
            assertThrows(IllegalStateException.class,
                    () -> EmbeddingResponse.vector("{\"data\":[{\"embedding\":\"abc\"}]}"));
        }

        @Test
        @DisplayName("embedding 是空陣列：零維向量沒有意義")
        void rejectsEmptyEmbedding() {
            assertThrows(IllegalStateException.class,
                    () -> EmbeddingResponse.vector("{\"data\":[{\"embedding\":[]}]}"));
        }

        @Test
        @DisplayName("陣列裡混進非數字，指出是第幾個")
        void rejectsNonNumericElement() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> EmbeddingResponse.vector("{\"data\":[{\"embedding\":[0.1,\"x\",0.3]}]}"));

            assertTrue(thrown.getMessage().contains("[1]"), thrown.getMessage());
        }

        @Test
        @DisplayName("回應不是 JSON 物件")
        void rejectsNonObject() {
            assertEquals("回應不是 JSON 物件",
                    assertThrows(IllegalStateException.class,
                            () -> EmbeddingResponse.vector("[1,2,3]")).getMessage());
        }
    }
}
