package eat.conversation.adapter.out.llamacpp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import eat.conversation.application.domain.model.Conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 core 的對話資料翻譯成 llama.cpp /v1/chat/completions 的 request body。
 *
 * 這個 class 就是「翻譯」本身：左邊是 domain 的型別（Conversation.Message、Role），
 * 右邊是協定的字串（"user"、"system"）。core 完全不知道右邊那一半存在。
 * 依賴方向是 adapter -> application，單向，反過來絕不允許。
 *
 * JSON 的機械工（跳脫、組字串）交給 Jackson —— 它是 Spring 帶進來的，本來就在 classpath 上，
 * 手寫一份只是多養一份要自己維護的程式碼。留在這個 class 裡的是 Jackson 幫不上忙的那一半：
 * 哪些欄位該送、domain 的 Role 對應到協定的哪個字串、系統指令要怎麼攤平。
 * 那些是決定，不是序列化 —— 也正是這個 class 存在的理由。
 */
final class ChatRequest {

    // ObjectMapper 設定好之後就是執行緒安全的，所以當常數重用；它不便宜，不該每次呼叫都 new
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatRequest() {
    }

    /**
     * 組出完整的 request body。
     *
     * 刻意不帶 model 欄位：server 是單模型常駐，這個欄位會被忽略（實測填任意值都回同一顆模型）。
     * 不寫比寫一個騙人的值誠實。之後的 OpenAI adapter 才需要它，那是那個 adapter 的事。
     *
     * 也刻意不帶 temperature、top_p 等取樣參數：server 啟動時已經設好（Qwen3 建議值），
     * adapter 沒有理由越權覆蓋。哪天真的要調，那是設定該做的事，不是寫死在這裡。
     */
    static String body(Optional<String> instruction, List<Conversation.Message> messages) {
        Objects.requireNonNull(instruction, "instruction 不可為 null");
        Objects.requireNonNull(messages, "messages 不可為 null");
        // port 契約說「最後一則是待回覆的提問」，空的代表呼叫端違約。
        // 早一點在這裡炸，比送出去讓 server 回 400 好除錯
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不可為空");
        }

        List<Message> items = new ArrayList<>();
        // 系統指令是協定裡的第一則 system 訊息。
        // 在 domain 它是 Conversation 的一個獨立欄位，不在 messages 裡 ——
        // 「攤平成訊息陣列」是協定的要求，所以這個轉換屬於 adapter
        instruction.ifPresent(text -> items.add(new Message("system", text)));
        for (Conversation.Message message : messages) {
            items.add(new Message(protocolRole(message.role()), message.text()));
        }

        return write(new Body(items, false));
    }

    private static String write(Body body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // 序列化的是我們自己在上面組好的資料，寫不出來代表程式接錯線，
            // 不是外界的問題，所以不當成「呼叫失敗」而是當成 bug 往外丟
            throw new IllegalStateException("組 request body 失敗", e);
        }
    }

    /**
     * domain 的 Role 轉成協定的字串。
     * 沒有 default 分支是故意的：哪天 Conversation 加了新的 Role，
     * 這裡會直接編譯失敗，逼你回來決定它在協定上該叫什麼，
     * 而不是安靜地走進 default 送出一個錯的角色
     */
    private static String protocolRole(Conversation.Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * 協定上的 request 形狀，也就是真正會被送出去的那份 JSON。
     *
     * 寫成 record 而不是手工串字串，最大的好處是「這個 adapter 送出什麼」一眼看得完：
     * 只有 messages 和 stream 兩個欄位 —— 沒有 model、沒有 temperature，不是漏寫，是刻意不送。
     * record 的元件順序就是 JSON 的欄位順序，Jackson 照宣告順序輸出。
     *
     * stream 明寫成 false：server 預設值本來就是 false，
     * 但寫出來讓「這個 adapter 不做串流」變成 body 上看得見的事實，而不是依賴預設。
     */
    record Body(List<Message> messages, boolean stream) {
    }

    record Message(String role, String content) {
    }
}
