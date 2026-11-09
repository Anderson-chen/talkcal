# SLO 快燒：error budget 幾天內就會燒光（`slo-fast-burn`）

| | |
|---|---|
| severity | **critical** —— 現在就處理 |
| 規則 | [`slo-burn-rate.yaml`](../../ops/grafana/provisioning/alerting/slo-burn-rate.yaml) 第 1 條；SLO 的定義與門檻見 [docs/slo.md](../slo.md) |
| 條件 | 任一 SLO：`1h` 和 `5m` 的 burn rate 都 > 13.44，或 `6h` 和 `30m` 都 > 5.6 |

## 代表什麼

某一個 SLO 的 error budget（28 天裡可以壞的份量）正以「2～5 天就燒光」的速度在燒，而且**現在還在燒**（短視窗也超過）。
信的標題寫了是哪一個 SLO、哪一組視窗：

| `slo` 標籤 | 壞的是什麼 |
|-----------|-----------|
| `calendar-availability` | 行程 API（和讀對話紀錄）回 5xx |
| `calendar-latency` | 行程 API 超過 300ms |
| `assistant-availability` | 跟助理說話回 5xx（含熔斷、滿載的 503） |
| `assistant-latency` | 跟助理說話超過 8 秒 |

這是**症狀**告警：使用者已經受影響。原因要往下找，通常同時會有一條原因類的告警（資料庫、連線池、熔斷）告訴你方向。

## 對使用者的影響

看 `slo`：可用性的是使用者拿到錯誤；延遲的是使用者在等。burn rate 13.44 代表壞的比例是預算的 13 倍 ——
以 `calendar-availability`（目標 99.5%）來說，約 6.7% 的請求在失敗。

## 怎麼確認

1. **talkcal — SLO** 儀表板（`/d/talkcal-slo`）：哪個 SLO 的 burn rate 越過虛線、從什麼時候開始、budget 還剩多少
2. 同時在響的告警：Grafana → Alerting → Alert rules，篩 Firing。有原因類的告警就直接照那份 runbook：

   | 同時響的 | 去哪 |
   |---------|------|
   | talkcal 資料庫連不上 | [talkcal-db-down](talkcal-db-down.md) |
   | talkcal 有請求等不到資料庫連線 | [talkcal-db-pool-timeout](talkcal-db-pool-timeout.md) |
   | talkcal 模型熔斷持續打開 | [talkcal-model-circuit-open](talkcal-model-circuit-open.md) |
   | talkcal app 掛了 | [talkcal-app-down](talkcal-app-down.md) |

3. 沒有原因類告警的話，自己找：

   ```promql
   slo:sli_error:ratio_rate5m                                     # 每個 SLO 現在壞的比例
   sum by (uri, status) (increase(http_server_requests_seconds_count{job="talkcal-app", status=~"5.."}[15m]))
   ```

   ```logql
   {service="app"} | json | http_response_status_code >= 500
   ```

## 可能的原因（由常見到少見）

### 1. 助理的模型出事（`assistant-availability`）

#44 的演練：模型斷線約 5 分鐘就會觸發這條。→ [talkcal-model-circuit-open](talkcal-model-circuit-open.md)

### 2. 資料庫停了或卡住（`calendar-availability`、`calendar-latency`）

#34 的演練：資料庫停掉約 7 分鐘觸發。→ [talkcal-db-down](talkcal-db-down.md)、[talkcal-db-pool-timeout](talkcal-db-pool-timeout.md)

### 3. 剛部署的新版有問題

時間點跟部署對得上 → 退回上一版：

```bash
sudo GIT_REF=<上一個 commit> bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

### 4. 模型塞車（`assistant-latency`）

同時用的人超過容量（本機的 llama-server 約同時 2 人）：llama-cpp dashboard 的排隊數（`requests_deferred`）很高、
每一次叫模型都正常、只是在排。SLO 文件量過：吞吐量在 2 人時就到頂，之後只是排隊。
短時間沒辦法加容量；確認不是異常流量（同一個人狂送），nginx 對 AI 發問有限流。

### 5. 流量太小造成的誤報

半夜一小時只有幾個請求，壞 1 個 burn rate 就很高（[docs/slo.md](../slo.md) 有寫這個限制）。
確認方式：看 `sum(increase(http_server_requests_seconds_count{job="talkcal-app"}[1h]))`，個位數就是這個情況。
處理：確認那幾個失敗是什麼；真的沒事就在下面紀錄成誤報。

## 怎麼確認已經恢復

- 短視窗（5m 或 30m）的 burn rate 掉到門檻以下，告警就解除（長視窗還很高是正常的，要好幾小時才降）
- 收到 `[RESOLVED]`
- 儀表板看這次燒掉多少 budget；剩不到一半的話，接下來部署要小心（Workbook 的建議：budget 燒完就停止上新功能）

## 什麼時候往上升級

- 30 分鐘內找不到原因 → 先止血：退回上一版、或（只有助理的話）可以先接受助理不能用，行程照常
- budget 已經燒光 → 這 28 天內只修 bug、不上新功能

## 事後要不要寫 postmortem

**要**（使用者受影響、而且燒掉一大塊 budget）。原因 5 的誤報例外：在下面紀錄，累積幾次就改 docs/slo.md 的做法（例如加最低請求數）。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#154）：模型斷線、資料庫停掉 | 見 PR #154 | |
