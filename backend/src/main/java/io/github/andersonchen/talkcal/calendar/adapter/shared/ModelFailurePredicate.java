package io.github.andersonchen.talkcal.calendar.adapter.shared;

import com.openai.errors.BadRequestException;
import com.openai.errors.UnprocessableEntityException;

import java.util.function.Predicate;

/**
 * 叫模型的這個失敗，算不算「模型出事」、要不要記進斷路器（#44）。
 *
 * 不算的只有「這一個請求本身有問題」：400（例如對話太長超過 context）、422。換一個請求就會好，
 * 算進去的話，一個使用者一直送超長的對話就能把所有人的助理關掉。
 * 其他都算：連不上、逾時、5xx、429（模型塞爆）、401／403（雲端的金鑰失效）、404（模型名稱設錯）——
 * 這些換一個請求也一樣失敗，正是斷路器要擋的。
 *
 * 沿著 cause 往下找：Spring AI 可能把 SDK 的例外再包一層。
 *
 * 是一個類別而不是一個方法：application.properties 的
 * resilience4j.circuitbreaker.instances.chat-model.record-failure-predicate 用類別名稱指到它，
 * starter 自己 new 一個來用（所以要有不帶參數的建構子）。
 */
public final class ModelFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BadRequestException || t instanceof UnprocessableEntityException) {
                return false;
            }
        }
        return true;
    }
}
