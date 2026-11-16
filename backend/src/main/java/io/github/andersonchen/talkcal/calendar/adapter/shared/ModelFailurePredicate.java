package io.github.andersonchen.talkcal.calendar.adapter.shared;

import com.openai.errors.BadRequestException;
import com.openai.errors.UnprocessableEntityException;

import java.util.function.Predicate;

/**
 * 呼叫模型的這次失敗算不算模型出錯，要不要記進斷路器。
 *
 * 不算的只有這個請求本身有問題：400（例如對話太長超過 context）、422。換一個請求就會好，
 * 算進去的話，一個使用者一直送超長的對話就能讓所有人的助理停用。
 * 其他都算：連不上、逾時、5xx，429 是模型滿載、401／403 是雲端的金鑰失效、404 是模型名稱設錯，
 * 這些換一個請求也一樣失敗，正是斷路器要擋的。
 *
 * 沿著 cause 往下找：Spring AI 可能把 SDK 的例外再包一層。
 *
 * 寫成類別：application.properties 的 resilience4j.circuitbreaker.instances.chat-model.record-failure-predicate
 * 用類別名稱指定，starter 自己建立實例，所以要有不帶參數的建構子。
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
