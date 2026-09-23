package eat.conversation.adapter.out.knowledge;

import eat.conversation.application.domain.model.Passage;

import java.util.List;

/**
 * 暫時寫死的知識庫，讓「檢索 → 提問 → 回覆」這條路先跑起來。
 *
 * 這些片段等於是「已經手動切好的 chunk」：每一段自己讀得懂，而且都掛著出處。
 * 真正的索引流程（讀檔、切段、存起來）是後面的事 —— 那條線長出來之後，這個類別就會被刪掉。
 *
 * 內容是中文的：上面那個 adapter 用單字比對，對英文幾乎無效。
 */
public final class SampleKnowledgeBase {

    private static final List<Passage> PASSAGES = List.of(
            new Passage(
                    "鮭魚富含 Omega-3 脂肪酸與優質蛋白質，每 100 公克約 200 大卡、含 20 公克蛋白質。",
                    "鮭魚.md > 營養成分"),
            new Passage(
                    "新鮮鮭魚需冷藏於 0 到 4 度，兩天內食用完畢；要長期保存就急速冷凍到零下 18 度，可放三個月。",
                    "鮭魚.md > 保存方式"),
            new Passage(
                    "香煎鮭魚先用廚房紙巾吸乾表面水分，皮朝下入鍋，中火煎四分鐘再翻面，避免過熟。",
                    "鮭魚.md > 烹調建議"),
            new Passage(
                    "雞胸肉幾乎不含脂肪，每 100 公克約 165 大卡、含 31 公克蛋白質，是增肌常見的蛋白質來源。",
                    "雞胸肉.md > 營養成分"),
            new Passage(
                    "雞胸肉容易乾柴，下鍋前先用鹽水浸泡三十分鐘，煎的時候中小火兩面各三分鐘，再燜兩分鐘。",
                    "雞胸肉.md > 烹調建議"),
            new Passage(
                    "酪梨的脂肪以單元不飽和脂肪酸為主，每 100 公克約 160 大卡，膳食纖維含量高。",
                    "酪梨.md > 營養成分"));

    private SampleKnowledgeBase() {
    }

    public static List<Passage> passages() {
        return PASSAGES;
    }
}
