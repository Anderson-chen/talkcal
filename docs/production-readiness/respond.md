# ④ 出事的時候（Respond）

[← 回上線準備清單](../production-readiness.md)

<a id="alerting"></a>
### 告警 ❌

Grafana 只有 dashboard，沒有 alert rule。沒有人盯著，等於沒有監控。

<a id="meta-monitoring"></a>
### 監控本身掛掉 ❌

Alloy 或 Mimir 停掉的時候，dashboard 只會變成一片空白，沒有人會知道（Google 清單裡的 “monitoring the monitoring”）。有了告警之後，要加一條「指標停止進來」的告警。

<a id="runbook"></a>
### Runbook ❌

告警響了之後該做什麼，沒有寫下來。

<a id="postmortem"></a>
### Postmortem ❌

還沒有任何一份事故檢討。

<a id="user-report-trace"></a>
### 從使用者回報追到 trace ❌

前端沒有顯示 trace id，使用者說「剛剛那次怪怪的」的時候，找不到是哪一次。補上之後，這個 trace id 就是[單一請求 dashboard](run.md#request-correlation) 的入口。

<a id="failure-ux"></a>
### 失敗時的使用者體驗 ❌（待確認）

還沒確認 LLM 掛掉或逾時的時候，前端會顯示什麼。

<a id="degradation"></a>
### 降級與熔斷 ❌

llama-server 掛掉，助理就跟著掛，除了逾時之外沒有其他保護。

<a id="backup"></a>
### 備份與還原 ❌

PostgreSQL 沒有備份，也沒有演練過還原。沒還原過的備份不算備份。
