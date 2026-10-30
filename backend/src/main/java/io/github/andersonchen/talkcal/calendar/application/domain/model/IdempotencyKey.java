package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.regex.Pattern;

/**
 * 冪等鍵（#51）：呼叫端替「這一次新增」取的名字。同一個名字再送一次，就是同一次新增的重送，不會多存一份。
 *
 * 由呼叫端產生、不由後端發：重送發生在「後端存好了、回應卻沒送到」的時候（逾時、502、斷線），
 * 呼叫端手上只有自己送出去的東西 —— 得是它在第一次送之前就決定好、重送時原樣再帶的名字。
 * 前端用 crypto.randomUUID()，但這裡不規定一定要是 UUID：別的呼叫端（腳本、之後的 agent）可能有自己的編法。
 *
 * 只收英數和 . _ : -、1～100 個字：夠放 UUID 和常見的「前綴:編號」，又不必擔心奇怪的字元進到 log 或資料庫。
 * 名字只在同一個擁有者底下才有意義（不同人用同一個名字互不相干，見 V13）。
 */
public record IdempotencyKey(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    public IdempotencyKey {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("Idempotency-Key 要是 1～100 個英數或 . _ : - 字元：" + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
