# 交付指標（DORA）

用數字回答「交付是變快了，還是變不穩了」。DORA 2025 的研究說 AI 是放大器：做得好的團隊用了更快、也一樣穩，
其他團隊產出變快，但出事的次數跟著變多。這個 repo 大部分的改動是跟 AI 一起寫的，不量就不知道自己是哪一種。

```bash
python cd/dora/dora.py                 # 全部歷史，環境 local，印出 markdown 報告
python cd/dora/dora.py --days 30       # 最近 30 天
python -m unittest discover -s cd/dora -v   # 反例測試
```

只讀 GitHub：`gh api` 的 GET 和 GraphQL query，不建 issue、label、deployment。要本機有登入的 `gh`。
每週一由 [`.github/workflows/dora.yml`](../../.github/workflows/dora.yml) 自動算一次，報告在那次 run 的摘要頁。

## 五個指標（DORA 2024 起的版本）

[DORA 官方](https://dora.dev/guides/dora-metrics/)目前是五個指標、分兩組。跟常見的「四個關鍵指標」差在：

- 2023：MTTR 改成**失敗部署復原時間**（failed deployment recovery time），只算「部署造成的」失敗，不算機房斷電這種跟改動無關的
- 2024：加了第五個**部署重工率**（deployment rework rate），並把五個分成速度（throughput）和不穩定（instability）兩組；
  復原時間歸在速度那組（[歷史](https://dora.dev/insights/dora-metrics-history/)）
- 2025：指標本身沒變。報告不再分 Elite／High／Medium／Low 四級，改成七種團隊型態，所以這裡不印「你是哪一級」

| 組 | 指標 | 官方定義（意譯） |
|---|---|---|
| 速度 | 變更前置時間 | 一個改動從 commit 到在線上跑起來要多久 |
| 速度 | 部署頻率 | 多久部署一次 |
| 速度 | 失敗部署復原時間 | 一次需要人立刻介入的失敗部署，要多久恢復 |
| 不穩定 | 變更失敗率 | 部署之後需要緊急處理（rollback、hotfix）的比例 |
| 不穩定 | 部署重工率 | 計畫外、因為線上出事才做的部署的比例 |

## 在這個 repo 怎麼算

### 資料從哪來

| 資料 | 來源 | 誰寫的 |
|---|---|---|
| 部署：哪個 commit、什麼時候、成功沒 | GitHub Deployments，環境 `local` | [cd/triggers/watch.sh](../triggers/watch.sh) 每次部署都寫；hotfix 手動部署照 [docs/hotfix.md](../../docs/hotfix.md) 補一筆 |
| 改動：第一個 commit、合併時間、合併後的 commit | 合進 `master` 的 PR | GitHub |
| 改動從什麼時候開始 | PR 用 `Closes #N` 連到的 issue 的開立時間 | 規則：每個改動都先開 issue |
| 線上事故 | label `bug`、標題「線上：」開頭的 issue | [docs/hotfix.md](../../docs/hotfix.md) 第 0 步 |
| `master` 上 commit 的先後 | commits API | `master` 是 squash merge，一條直線，「部署 X 包含 commit Y」＝ X 比 Y 新或就是 Y |

`github-pages` 環境的部署不算：那是架構圖網頁，不是線上服務。

**部署的時間點**一律用終態（success／failure）寫回的時間，不是開始部署的時間：成功寫回時新版本才真的在線上。
只有 `in_progress`、沒有終態的部署（watcher 中途被砍）不知道結果，不進分母，報告另外列出筆數。

### 部署頻率

區間內成功部署的次數、每天幾次、有部署的天數，以及其中有幾個不同的版本（同一個 commit 重試會重複算次數）。

### 變更前置時間：印兩個

| | 起點 | 終點 |
|---|---|---|
| DORA 標準 | PR 第一個 commit 的 authoredDate | 第一個**包含**這個 PR 合併 commit 的成功部署完成 |
| talkcal 多看的 | PR `Closes` 的 issue 裡最早開的那個 | 同上 |

- 終點用「包含」不是「等於」：watcher 只部署最新的綠燈 commit，中間幾個 PR 可能是跟著下一個版本一起上線的
- 起點用 authoredDate：rebase 會改 committedDate，authoredDate 才是第一次寫下這個改動的時間
- 為什麼多看 issue：只從 commit 算，看不到「issue 開了但一直沒人動」的等待。issue 單提到「要有 issue 的開立時間才量得出來」
  就是這個。兩個差很多代表卡在開工前，差不多代表卡在開發或審查
- issue 比 commit 晚開是可能的（先修再補 issue），所以 issue→上線 偶爾比 commit→上線 短，不是算錯
- **這個環境第一筆部署之前合併的 PR 不算**：那時候還沒有 CD，它們都在 CD 做好的那天才「上線」，量到的是 CD 多晚做好，不是交付速度
- 走 hotfix 分支的修正：線上先由 hotfix 部署修好，但 `master` 那個 PR 的前置時間仍以 `master` 版本上線為準（會比實際長一點）

### 變更失敗：三種訊號，任一個成立就算

| 訊號 | 哪一筆部署算失敗 | 從什麼時候開始壞 | 什麼時候恢復 |
|---|---|---|---|
| 部署本身失敗（status `failure`／`error`） | 那一筆 | 那次部署開始 | 下一個成功部署完成 |
| rollback：成功部署了一個**以前成功上線過、而且比線上舊**的 `master` commit | rollback 前線上那一筆 | 那一版上線 | rollback 完成 |
| 線上事故 issue | 開 issue 當下線上那一筆 | 開 issue | 第一個包含修正的成功部署完成：`master` 上 `Closes` 這個 issue 的 PR，或描述寫著 `#N` 的 hotfix 分支部署 |

同一筆部署被好幾個訊號標到只算一次，開始時間取最早的、恢復時間取最早的。

為什麼這樣判斷：

- **部署失敗算**：watcher 失敗時 `apply.sh` 可能已經換掉一部分服務，需要人去看。DORA 的定義是「需要立刻介入」，這符合。
  同一個 commit 失敗後不會自動重試（watch.sh 的設計），所以一次失敗就是一筆
- **rollback 從資料判斷，不靠描述文字**：[#19](https://github.com/Anderson-chen/talkcal/issues/19) 的 rollback 做法還沒定，
  不管它最後寫什麼描述，「部署回以前上過的舊版本」這件事在 Deployments 上一定看得到。
  同一個 commit 再部署一次是重試，rollback 之後再部署回比較新的版本是往前走，兩個都不算
- **事故用 issue 判斷**：hotfix 流程第 0 步一定開「線上：」issue；只有 label `bug` 但不是「線上：」開頭的是開發中發現的 bug，
  不是線上事故，不算
- **postmortem 不直接當訊號**：[docs/postmortems/](../../docs/postmortems/) 是 markdown，沒有能可靠對到哪一筆部署的欄位；
  寫 postmortem 的事故一定先有「線上：」issue，已經從那裡算到了。目前唯一一份（2026-10-12）是本機演練，而且早於第一筆部署紀錄

**變更失敗率** ＝ 失敗的部署 ÷ 有結果的部署嘗試（成功＋失敗）。

**失敗部署復原時間** ＝ 恢復 − 開始，報告印中位數和每一筆。還沒恢復的列「還沒恢復」，不進中位數。

### 部署重工率

計畫外、為了修線上問題的成功部署 ÷ 成功部署。算重工的：

- rollback 那一筆
- 包含事故修正、讓事故恢復的那一筆
- 不在 `master` 上的成功部署（從 hotfix 分支手動部署的）

部署失敗之後的**下一次成功不算**重工：watcher 只會部署 `master` 下一個綠燈版本，那是平常的下一次部署，不是因為出事才排的。

## 為什麼每週一在 GitHub Actions 跑，不推進 Grafana

| | Actions 排程＋摘要頁（選這個） | 推進 Mimir、Grafana 畫圖 |
|---|---|---|
| 資料在哪 | 本來就在 GitHub，在 GitHub 上算 | 要把 GitHub 的資料搬到部署機器 |
| 權限 | 用 workflow 內建的唯讀 token | 部署機器要多一個能讀 repo 的 token，或 GitHub 要能連進機器（違反 [cd/README.md](../README.md)「為什麼是 pull」） |
| 機器關機時 | 照跑 | Mimir 跟著關，資料斷掉（EC2 平常關機） |
| 資料的樣子 | 一週幾十筆事件，表格每一列點得回部署和 PR | Prometheus 是給每 15 秒取樣的時間序列設計的，事件要轉成計數器才放得進去 |

報告和原始資料（snapshot JSON）存成 artifact 90 天：之後改了定義，可以用 `--snapshot` 拿舊資料重算比較。
等之後部署量大到要看趨勢圖，再考慮讓 Alloy 定期跑這支、轉成指標。

## 限制

- **環境只有 `local`**：EC2 是手動部署、沒有寫 Deployments（見 [docs/hotfix.md](../../docs/hotfix.md)）。EC2 開始寫之後用 `--env` 換
- **一個人的專案**：「等審查」幾乎是零，前置時間主要反映開發本身。數字是拿來跟自己的過去比，不是跟業界比
- **依賴還沒合併的流程**：hotfix 的 issue 標題和部署描述格式來自 [PR #131](https://github.com/Anderson-chen/talkcal/pull/131)；
  rollback（#19）還沒做，所以目前的歷史裡 rollback 和事故都是 0 筆，這兩種判斷只由反例測試證明
