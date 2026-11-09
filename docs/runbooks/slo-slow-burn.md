# SLO 慢燒：照這速度這個月會不達標（`slo-slow-burn`）

| | |
|---|---|
| severity | warning —— 開 issue，上班時間處理 |
| 規則 | [`slo-burn-rate.yaml`](../../ops/grafana/provisioning/alerting/slo-burn-rate.yaml) 第 2 條；SLO 的定義與門檻見 [docs/slo.md](../slo.md) |
| 條件 | 任一 SLO：`1d` 和 `2h` 的 burn rate 都 > 2.8，或 `3d` 和 `6h` 都 > 0.933 |

## 代表什麼

不是急事，但某個 SLO 長時間一直小量在壞：照這個速度，28 天的 error budget 在 10 天內（1d 那組）或這個月內（3d 那組）會用完。
常見是「一直有一點點慢」或「固定某個情況會失敗」，單次都不夠觸發快燒。

## 對使用者的影響

小量、持續：例如每 100 個請求有 1～2 個慢或失敗。個別使用者偶爾遇到，不會覺得整個壞了。

## 怎麼確認

1. **talkcal — SLO** 儀表板：哪個 SLO、從哪天開始往下掉
2. 這段時間壞的請求集中在哪：

   ```promql
   sum by (uri, status) (increase(http_server_requests_seconds_count{job="talkcal-app", status=~"5.."}[1d]))
   # 延遲的 SLO：超過門檻的比例，按 uri 分
   1 - sum by (uri) (rate(http_server_requests_seconds_bucket{job="talkcal-app", le="0.3"}[1d])) / sum by (uri) (rate(http_server_requests_seconds_count{job="talkcal-app"}[1d]))
   ```

3. 慢的請求用 exemplar 點進 trace，看共同點（同一個使用者？資料很多的月份？）

## 可能的原因（由常見到少見）

### 1. 某一支 API 隨著資料變多越來越慢

延遲集中在某一個 uri（例如列月曆）。trace 看 SQL 那段 → 缺索引、一次撈太多。寫 issue，用 migration 加索引或改查詢。

### 2. 某種輸入固定失敗

5xx 集中在某個 uri，log 的例外都一樣。寫 issue、補測試、修。

### 3. 助理在某種問題上常常很久

`assistant-latency` 慢燒：看慢的對話，是不是某類問題會讓 agent loop 打轉很多輪。改 system prompt 或工具，走 PR。

### 4. 驗證環境的資料太少（不是真的出事）

剛起的環境只有幾小時的資料，1d、3d 的視窗看到的其實是那幾小時（#154 遇過：budget 算出 −1338%）。
環境跑不滿 3 天時這條的結果不可信，確認後記成誤報。

## 怎麼確認已經恢復

修好之後短視窗（2h 或 6h）降到門檻以下就解除；長視窗要一兩天才降，不用等它。

## 什麼時候往上升級

- 慢燒持續了幾天、budget 剩不到 30% → 這個月剩下的時間先把修這個問題排在新功能前面

## 事後要不要寫 postmortem

不必寫完整的 postmortem，開 issue 追就好；issue 裡寫清楚是哪個 SLO、燒掉多少、原因。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#154）：只有 1 小時的資料，長視窗跟著響（原因 4） | — | 見 PR #154 |
