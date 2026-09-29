package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EmbeddingRequest")
class EmbeddingRequestTest {

    @Test
    @DisplayName("只送 input 與 encoding_format：沒有 model 不是漏寫，是刻意不送")
    void sendsOnlyInputAndEncodingFormat() {
        assertEquals("{\"input\":\"鮭魚\",\"encoding_format\":\"float\"}",
                EmbeddingRequest.body("鮭魚"));
    }

    @Test
    @DisplayName("特殊字元交給 Jackson 跳脫，讀回來跟原文一模一樣")
    void escapesSpecialCharacters() throws Exception {
        String original = "他說「\"讚\"」\n下一行\t結束";

        String body = EmbeddingRequest.body(original);

        // 驗「跳脫後的字面長怎樣」會綁死在某一種跳脫寫法上，而且測試自己也要跟著多跳一層。
        // 真正該成立的性質是這兩條：
        // 一、真正的控制字元不會原封塞進 JSON（塞進去 body 就壞了）
        assertFalse(body.contains("\n"), "JSON 裡不該有真正的換行：" + body);
        assertFalse(body.contains("\t"), "JSON 裡不該有真正的 tab：" + body);
        // 二、讀回來要跟原文完全相同 —— 跳脫對不對，讓剖析器自己說了算
        assertEquals(original, new ObjectMapper().readTree(body).get("input").asString());
    }

    @ParameterizedTest(name = "文字 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("空白文字算不出有意義的向量，在送出前就擋下來")
    void rejectBlankText(String text) {
        assertThrows(IllegalArgumentException.class, () -> EmbeddingRequest.body(text));
    }
}
