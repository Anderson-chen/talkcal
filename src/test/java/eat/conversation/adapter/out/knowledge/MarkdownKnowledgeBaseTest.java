package eat.conversation.adapter.out.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

@DisplayName("MarkdownKnowledgeBase")
class MarkdownKnowledgeBaseTest {

    // 一段的內文與出處。Document 本身不好直接比：每個都帶著一個隨機的 id
    private record Section(String text, String source) {
    }

    // 把 Document 拆回內文與出處，順便驗兩件事：文字是「出處 + 換行 + 內文」、metadata 裡的出處跟文字裡的一致
    private static List<Section> sections(List<Document> documents) {
        return documents.stream().map(document -> {
            String source = source(document);
            String prefix = source + "\n";
            assertTrue(document.getText().startsWith(prefix), "文字沒有以出處開頭：" + document.getText());
            return new Section(document.getText().substring(prefix.length()), source);
        }).toList();
    }

    private static String source(Document document) {
        return (String) document.getMetadata().get(MarkdownKnowledgeBase.SOURCE);
    }

    @Nested
    @DisplayName("照 ## 標題切段")
    class Chunking {

        @Test
        @DisplayName("每個 ## 一段，出處是「檔名 > 標題」，# 文件標題不算進任何一段")
        void splitsAtSecondLevelHeadings() {
            String markdown = """
                    # 鮭魚

                    ## 營養成分

                    富含 Omega-3。

                    ## 烹調建議

                    中火煎四分鐘。
                    """;

            assertEquals(List.of(
                    new Section("富含 Omega-3。", "鮭魚.md > 營養成分"),
                    new Section("中火煎四分鐘。", "鮭魚.md > 烹調建議")),
                    sections(MarkdownKnowledgeBase.chunk("鮭魚.md", markdown)));
        }

        @Test
        @DisplayName("### 不再往下切，留在所屬那一段的內文裡")
        void keepsDeeperHeadingsInsideTheSection() {
            String markdown = """
                    ## 烹調建議
                    香煎：中火煎四分鐘。
                    ### 小技巧
                    先吸乾水分。
                    """;

            assertEquals(List.of(new Section("香煎：中火煎四分鐘。\n### 小技巧\n先吸乾水分。", "鮭魚.md > 烹調建議")),
                    sections(MarkdownKnowledgeBase.chunk("鮭魚.md", markdown)));
        }

        @Test
        @DisplayName("第一個 ## 之前的內文屬於整份文件，出處只有檔名，不能丟掉")
        void keepsTextBeforeTheFirstHeading() {
            String markdown = """
                    # 鮭魚
                    常見的高蛋白魚類。
                    ## 營養成分
                    富含 Omega-3。
                    """;

            assertEquals(List.of(
                    new Section("常見的高蛋白魚類。", "鮭魚.md"),
                    new Section("富含 Omega-3。", "鮭魚.md > 營養成分")),
                    sections(MarkdownKnowledgeBase.chunk("鮭魚.md", markdown)));
        }

        @Test
        @DisplayName("有標題沒內文的段落略過")
        void skipsEmptySections() {
            String markdown = """
                    ## 營養成分

                    ## 烹調建議
                    中火煎四分鐘。
                    """;

            assertEquals(List.of(new Section("中火煎四分鐘。", "鮭魚.md > 烹調建議")),
                    sections(MarkdownKnowledgeBase.chunk("鮭魚.md", markdown)));
        }

        @Test
        @DisplayName("CRLF 換行：出處和內文尾巴都不會黏著 \\r")
        void handlesWindowsLineEndings() {
            // 這台機器的 git 會在 checkout 時把 .md 轉成 CRLF，所以這不是假想情境
            String markdown = "# 鮭魚\r\n\r\n## 營養成分\r\n\r\n富含 Omega-3。\r\n";

            assertEquals(List.of(new Section("富含 Omega-3。", "鮭魚.md > 營養成分")),
                    sections(MarkdownKnowledgeBase.chunk("鮭魚.md", markdown)));
        }
    }

    /**
     * 對真的檔案：守的是「檔案都在、都讀得到、中文沒壞」。
     * 只比出處，不比內文 —— 內文是知識，會改；改了內文不該讓這題紅。
     * 內文切得對不對，由上面那組和對真模型的檢索品質測試守著。
     */
    @Test
    @DisplayName("打包進 classpath 的三份文件切出 6 段，出處的中文沒有變成亂碼")
    void loadsBundledFiles() {
        assertEquals(List.of(
                        "鮭魚.md > 營養成分",
                        "鮭魚.md > 保存方式",
                        "鮭魚.md > 烹調建議",
                        "雞胸肉.md > 營養成分",
                        "雞胸肉.md > 烹調建議",
                        "酪梨.md > 營養成分"),
                MarkdownKnowledgeBase.documents().stream().map(MarkdownKnowledgeBaseTest::source).toList());
    }
}
