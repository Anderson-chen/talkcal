package adapter.out.llamacpp;

import application.domain.model.Conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 把 core 的對話資料翻譯成 llama.cpp /v1/chat/completions 的 request body。
 *
 * 這個 class 就是「翻譯」本身：左邊是 domain 的型別（Conversation.Message、Role），
 * 右邊是協定的字串（"user"、"system"）。core 完全不知道右邊那一半存在。
 * 依賴方向是 adapter -> application，單向，反過來絕不允許。
 */
final class ChatRequest {

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

        StringJoiner items = new StringJoiner(",", "[", "]");
        // 系統指令是協定裡的第一則 system 訊息。
        // 在 domain 它是 Conversation 的一個獨立欄位，不在 messages 裡 ——
        // 「攤平成訊息陣列」是協定的要求，所以這個轉換屬於 adapter
        instruction.ifPresent(text -> items.add(messageObject("system", text)));
        for (Conversation.Message message : messages) {
            items.add(messageObject(protocolRole(message.role()), message.text()));
        }

        // stream 明寫成 false：server 預設值本來就是 false，
        // 但寫出來讓「這個 adapter 不做串流」變成 body 上看得見的事實，而不是依賴預設
        return "{\"messages\":" + items + ",\"stream\":false}";
    }

    private static String messageObject(String role, String content) {
        // role 是我們自己給的固定字串，其實不需要跳脫，
        // 但一律走 Json.string 才不會替「這個我確定安全」開後門 —— 那種後門最後都會被踩到
        return "{\"role\":" + Json.string(role) + ",\"content\":" + Json.string(content) + "}";
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
}
