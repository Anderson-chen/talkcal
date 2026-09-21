package adapter.out.llamacpp;

import java.util.Objects;

/**
 * 手寫的最小 JSON 工具：只做這個 Adapter 真正需要的事。
 *
 * 刻意不是 public —— JSON 是 llama.cpp HTTP 協定的細節，
 * core（domain / port / service）永遠不該看見這個型別。
 * 能不能編譯得過，就是「髒活有沒有關在 Adapter 裡」的第一道檢查。
 */
final class Json {

    // 工具類不需要實例；私有建構子讓「不要 new 我」變成編譯器保證，而不是口頭約定
    private Json() {
    }

    /**
     * 把原始文字變成「含前後雙引號」的 JSON 字串字面值。
     * 回傳值自帶引號，組 body 時可以直接貼，不必在呼叫端補引號而漏掉。
     */
    static String string(String raw) {
        // 文字在 model 就保證非 null（Conversation 規則 4）。
        // 這裡拿到 null 代表程式接錯線，要當場炸，而不是安靜地送出 "null" 四個字給模型
        Objects.requireNonNull(raw, "raw 不可為 null");

        StringBuilder out = new StringBuilder(raw.length() + 2);
        out.append('"');
        // 逐字元 switch，而不是一連串 replace()：
        // replace 串接有順序陷阱（先換引號再換反斜線，會把剛加上的跳脫反斜線又跳脫一次），
        // 逐字元掃描時每個字元只被判斷一次，順序問題從根本上不存在
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                // 不跳脫的話會提前關閉字串，整個 body 壞掉
                case '"' -> out.append("\\\"");
                // 反斜線本身是跳脫字元，不處理的話 Windows 路徑會被伺服器讀成控制字元
                case '\\' -> out.append("\\\\");
                // JSON 規格禁止字串內出現裸的控制字元。以下五個有短形式，比 u 跳脫好讀
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // 其餘控制字元沒有短形式，一律轉成反斜線 u 加四位十六進位
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        // 其他字元原樣輸出。中文不需要跳脫 —— 送出時會指定 UTF-8，
                        // 硬轉成跳脫形式只會讓 body 變大又難除錯
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
