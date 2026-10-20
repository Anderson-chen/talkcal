# Hotfix 流程

線上出問題、要緊急修正時怎麼做。一句話：**走平常那條路，只是插隊**。不跳過 CI，不直推 `master`，也照樣開 issue。

（[#26](https://github.com/Anderson-chen/talkcal/issues/26)，2026-10-16 寫下，2026-10-20 改版。分支策略、CI、CD 本身見 [docs/architecture.md](architecture.md#cicd從-commit-到線上) 和 [cd/README.md](../cd/README.md)；
整個 trunk-based 流程的總覽在 [#178](https://github.com/Anderson-chen/talkcal/issues/178) 規劃中）

## 出事時的順序：先止血，再修

hotfix 是「往前修」，通常不是第一步。照這個順序，前一步做得到就先做：

| 順序 | 做什麼 | 什麼時候用 | talkcal 現在 |
|---|---|---|---|
| 1 | **退回上一版**（rollback） | 上一版沒有這個問題，而且退得回去 | 正式指令在 [#19](https://github.com/Anderson-chen/talkcal/issues/19)（PR [#140](https://github.com/Anderson-chen/talkcal/pull/140) 的 `cd/rollback.sh local --previous`，還沒合併）。合併前只能手動：暫停 watcher，在部署用的 clone checkout 上一版、跑 `cd/apply.sh local` |
| 2 | **關掉出問題的功能**（kill switch） | 問題出在某個可以單獨關掉的功能 | **沒有**：repo 裡沒有 feature flag，這一步目前不存在 |
| 3 | **往前修**（這份文件） | 退不回去，或退回去也一樣壞 | 情境 A／B，見下面 |

退不回去的典型情況：新版跑過一個舊版不相容的 migration（例如欄位改成 NOT NULL，舊版寫入會失敗；見 [#18](https://github.com/Anderson-chen/talkcal/issues/18) 的 expand/contract），
或這個 bug 在上一版就已經有了。這時只能往前修。

止血之後不代表結束：退版會讓 `master` 和線上分開，修好之後還是要照下面的流程讓修正上線。

## 先判斷走哪一條

照順序問，第一個「是」就停：

1. **`master` 上比線上多出來的東西都能上線嗎？** → 是：**情境 A**
2. **不能上線的那些，能 revert 掉嗎？** → 是：開 PR revert 它們（`git revert <sha>`），合併後 `master` 又能上線 → **情境 A**
3. 都不行 → **情境 B**

| | 情境 A：`master` 可以直接上線（預設） | 情境 B：`master` 上有東西不能上線、也 revert 不掉 |
|---|---|---|
| 從哪裡開分支 | `master` | **線上那一版** |
| 怎麼部署 | 合併後 watcher 自己部署（跟平常一樣） | 先暫停 watcher，手動部署 hotfix 分支 |
| CI | 跟平常一樣：PR 一次、合併後 `master` 一次 | 修正進 `master` 的 PR 照常；hotfix 分支另外手動觸發一次 |
| 修正怎麼回到 `master` | 本來就在 `master` | 修正先進 `master`，再 cherry-pick 到 hotfix 分支 |

revert 不掉的情況：要 revert 的東西已經跑過 migration、跟其他改動纏在一起拆不開，或根本說不清是哪幾個 commit 不能上。

在 talkcal，本機的 CD 會把**每一個**綠燈的 `master` commit 自動部署（[cd/README.md](../cd/README.md#本機-cdmaster-綠燈就自動部署)），
所以 `master` 幾乎就等於線上，絕大多數都是情境 A。`master` 跟線上會分開，只在這幾種時候：

- 最新的 `master` 部署失敗：watcher 同一個 commit 只試一次，線上停在上一版，而 `master` 最新那個已知是壞的
- 退過版、或 watcher 被暫停了，`master` 已經往前走
- EC2：它是手動部署的，平常關機，線上可能比 `master` 舊很多

查 `master` 比線上多了什麼（線上是哪個 commit 見[情境 B 第 1 步](#1-找出線上跑的是哪個-commit)）：

```bash
git fetch origin
git log --oneline <線上的 sha>..origin/master   # 列出來的就是「master 有、線上沒有」的東西
```

## 兩條都一樣的第 0 步：開 issue

```bash
gh issue create --project talkcal --label bug --title "線上：<症狀>" --body "<影響、從什麼時候開始、怎麼發現的>"
```

開完在看板上把 Status 設成 In Progress。緊急不等於不留紀錄：之後從 commit → PR → issue 點得回「當時發生什麼事」，
事後檢討的時間軸也從這裡開始算。

## 情境 A：`master` 可以直接上線

步驟跟平常開分支、發 PR 一模一樣，**這是刻意的**：trunk-based 的緊急修正不另走一條路，緊急的時候才不會走到沒走過、沒人驗證過的路。
跟平常不同的只有這幾點：

- 先開 `bug` issue（第 0 步），其他事情排到它後面
- PR 裡一定要有重現這個 bug 的測試
- 合併後不等 watcher 的 5 分鐘，立刻叫它部署
- 事後要檢討（見[事後](#事後)）

```bash
git switch -c <issue 編號>-hotfix-<描述> origin/master
# 修正 + 一個重現這個 bug 的測試（測試跟修正放同一個 commit）
git commit -m "[fix] <描述>（#<issue 編號>）"
git push -u origin HEAD                        # pre-push 在本機先跑一次 CI
gh pr create --fill --body "Closes #<issue 編號>"
gh pr checks --watch                           # 等 CI（約 2 分 20 秒）
gh pr merge --squash --delete-branch
```

合併後 `master` 再跑一次 CI，綠燈後立刻叫 watcher 跑一輪：

```powershell
Start-ScheduledTask talkcal-cd-local
```

EC2：`master` 綠燈後用 SSM 連進去重跑 `sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh`
（[infra/envs/1-ec2](../infra/envs/1-ec2/README.md)）。

確認上線：GitHub repo 頁面的 Deployments → `local` 最新一筆是這個 commit、狀態 success，再實際打一次出問題的功能。

## 情境 B：`master` 上有東西不能上線、也 revert 不掉

### 1. 找出線上跑的是哪個 commit

**之後**（[#25](https://github.com/Anderson-chen/talkcal/issues/25) 版本與 release tag 合併後）：直接用線上那一版的 tag。

**現在**還沒有 tag，用 GitHub Deployments 裡最新一筆成功的紀錄（watcher 每次部署都會寫）：

```bash
for id in $(gh api "repos/Anderson-chen/talkcal/deployments?environment=local&per_page=20" --jq '.[].id'); do
  [ "$(gh api "repos/Anderson-chen/talkcal/deployments/$id/statuses" --jq '.[0].state')" = success ] \
    && { gh api "repos/Anderson-chen/talkcal/deployments/$id" --jq .sha; break; }
done
```

不看「部署用的 clone 目前 checkout 在哪」：部署失敗時它停在失敗的那個 commit，不是線上跑的那個。
EC2 沒有 Deployments 紀錄，用 SSM 連進去看 `git -C /opt/talkcal/repo log -1`（`deploy.sh` 失敗時同樣不準，要搭配 `docker ps` 的容器建立時間判斷）。

### 2. 暫停 watcher，修正先進 `master`

```powershell
Disable-ScheduledTask talkcal-cd-local
```

一定要在合併**之前**暫停：不然 `master` 綠燈後 watcher 會把整個 `master` 部署上去，包括那些還不能上線的東西，
之後也會把第 5 步手動部署的 hotfix 蓋掉。（PR [#140](https://github.com/Anderson-chen/talkcal/pull/140) 合併後改用它的「釘住」，兩種暫停方式的統一在 [#178](https://github.com/Anderson-chen/talkcal/issues/178)）

然後照[情境 A](#情境-amaster-可以直接上線) 開 PR、CI 綠燈、合併。

如果 bug 在 `master` 上已經不存在（程式碼改掉了），就跳過這步、直接在第 3 步的分支上修，上線後再把修正 cherry-pick 回 `master` 開 PR，並說明為什麼 `master` 不需要或需要不同的修法。

### 3. 從線上那一版開 hotfix 分支，把修正 cherry-pick 過去

```bash
live=<第 1 步查到的 sha>                       # 有 tag 之後：live=v<版本>
git fetch origin
git switch -c hotfix/<issue 編號>-<描述> "$live"
git cherry-pick -x <master 上修正的那個 commit>   # -x 在訊息裡記下「從哪個 commit 來的」
git push -u origin HEAD
```

名稱用 `hotfix/` 開頭：一看就知道這條分支是什麼、什麼時候該刪；#25 的 `cd/release.sh` 也只接受 `master` 或 `hotfix/*` 上的 commit 打 release tag。
這條分支不發 PR —— 要發也只能發到 `master`，diff 會是「線上到 `master` 之間的所有東西」反過來，沒有意義。

cherry-pick 有衝突代表線上那一版跟 `master` 已經分得很開：照衝突的內容手動改，改的東西要在第 4 步的 CI 裡被測到。

### 4. 手動觸發 hotfix 分支的 CI，等它綠燈

push 不會觸發 CI（[ci.yml](../.github/workflows/ci.yml) 的 push 只認 `master`，PR 這條分支也沒有），用 ci.yml 本來就有的手動觸發：

```bash
gh workflow run ci.yml --ref hotfix/<issue 編號>-<描述>
gh run list --workflow ci.yml --branch hotfix/<issue 編號>-<描述> --limit 1
gh run watch <上面的 run id> --exit-status     # 三個 job（checks、secrets、vulns）都要綠
```

hotfix 分支沒有 branch protection 擋「CI 沒綠就部署」，這一關靠人。CI 紅了就跟平常一樣修到綠，**不部署紅燈的版本**。
忘了觸發的話也等不到綠燈，部署前會發現。

### 5. 部署 hotfix 分支

在部署用的 clone（不是開發用的那份，理由見 [cd/README.md](../cd/README.md#第一次設定)）：

```bash
cd /c/talkcal-deploy
sha=$(git ls-remote origin refs/heads/hotfix/<issue 編號>-<描述> | cut -f1)   # 就是剛剛 CI 綠燈的那個
git fetch origin "$sha" && git checkout --detach "$sha"
cd/apply.sh local
```

手動部署不會寫 Deployments，補一筆，不然第 1 步之後就查不到「線上是 hotfix 這一版」：

```bash
id=$(gh api repos/Anderson-chen/talkcal/deployments --jq .id --input - <<EOF
{"ref": "$sha", "environment": "local", "auto_merge": false, "required_contexts": [],
 "description": "hotfix #<issue 編號>（手動）"}
EOF
)
gh api "repos/Anderson-chen/talkcal/deployments/$id/statuses" -f state=success -f description="hotfix 手動部署"
```

EC2：`sudo GIT_REF=hotfix/<issue 編號>-<描述> bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh`。

#25 合併後，上線的這一版另外打一個修訂版號的 tag（例如線上是 `v0.2.0`，這次就是 `v0.2.1`），hotfix 分支刪掉之後還查得到：

```bash
cd/release.sh v0.2.1 origin/hotfix/<issue 編號>-<描述>
```

### 6. 確認修正在 `master` 上，再恢復 watcher

```bash
git fetch origin
git cherry -v origin/master origin/hotfix/<issue 編號>-<描述> "$live"
```

`git cherry` 列出 hotfix 分支上、`live` 之後的每個 commit：`-` 開頭是 `master` 已經有同樣的修改，`+` 開頭是 **`master` 還沒有**。
全部都是 `-` 才算回到 `master` 了。內容被衝突改過的 commit 會顯示成 `+`，這時用 `-x` 留下的來源 commit 對照：

```bash
git log origin/master --oneline --grep "<cherry-pick 訊息裡的來源 sha>"   # 或直接看修正的測試在不在 master 上
```

`master` 恢復成可以上線（不能上的東西修掉或拿掉）之後：

```powershell
Enable-ScheduledTask talkcal-cd-local
Start-ScheduledTask talkcal-cd-local           # 部署 master 最新的綠燈版本，裡面已經有這次的修正
```

部署成功後**當天**刪掉 hotfix 分支：`git push origin --delete hotfix/<issue 編號>-<描述>`。它只活到 `master` 重新上線為止。

## 事後

| 什麼 | 怎麼做 |
|---|---|
| issue | 修正的 PR 寫 `Closes #N`，合併時自動關閉；情境 B 在 issue 留言寫上線的 sha（或 tag）和時間 |
| 回歸測試 | 修正的 PR 裡一定要有重現這個 bug 的測試，不然下次改動又會壞回去 |
| 事後檢討 | 有使用者受到影響、或花了超過一小時，就寫一份到 [docs/postmortems/](postmortems/)，格式照 [2026-10-12 那份](postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)：blameless、時間軸、為什麼沒被擋下來、後續 issue |
| 後續 | 檢討裡找到的「為什麼 CI 沒抓到」各開一張 issue |

## 為什麼這樣選

### 跟 Git Flow 的對照

Git Flow 用長期分支解決的問題，trunk-based 各有替代做法。hotfix 只是其中一列：

| Git Flow 靠什麼 | 解決什麼問題 | trunk-based 的替代做法 | talkcal 追蹤 |
|---|---|---|---|
| `develop` 分支 | 整合緩衝，壞了不影響正式版 | CI＋branch protection＋pre-push 在本機先跑 | 已完成 |
| `release/*` 分支 | 上線前凍結、穩定化 | 在 `master` 打 release tag，先上 staging 驗證 | [#25](https://github.com/Anderson-chen/talkcal/issues/25)、[#24](https://github.com/Anderson-chen/talkcal/issues/24) |
| `hotfix/*` 分支 | 緊急修正不帶進未完成的功能 | 修在 `master`；不能上線的東西先 revert；revert 不掉才從線上那一版（有 tag 用 tag）開短命分支，把 `master` 的修正 cherry-pick 過去 | [#26](https://github.com/Anderson-chen/talkcal/issues/26)（這份文件） |
| 功能做完才合併 | 半成品不會上線 | feature flag、branch by abstraction（先加抽象層，新舊實作並存再切換） | 沒有 issue |
| release 前人工把關 | 擋下有問題的版本 | canary 先放一小部分流量，壞了就 rollback | [#20](https://github.com/Anderson-chen/talkcal/issues/20)、[#19](https://github.com/Anderson-chen/talkcal/issues/19) |
| 多版本平行維護 | 同時支援 v1、v2 | 不需要，talkcal 只有線上一版 | 不適用 |
| （沒有） | 知道流程有沒有在退化 | DORA 指標，可以多量一個「分支存活時間」 | [#29](https://github.com/Anderson-chen/talkcal/issues/29) |

Git Flow 的 `hotfix/*` 從 `main` 開、合回 `main` 和 `develop`，是給「同時維護好幾個發行版」用的。talkcal 只有線上那一版，
所以只借它的名字，分支本身是情境 B 才開、上線後當天就刪的短命分支（分支策略見 #17）。

### 能 revert 就 revert，revert 不掉才開 hotfix 分支

revert 是 trunk-based 最常用、也最便宜的做法：`git revert` 是在 `master` 上**加**一個反向的 commit，不改歷史，
走的就是平常的 PR 和 CI，部署也交給 watcher。之後要把那個功能加回來，再 revert 一次 revert 的 commit 就好。

hotfix 分支要暫停 watcher、手動觸發 CI、手動部署、事後用 `git cherry` 確認，步驟多、每一步都靠人，所以只留給 revert 不掉的時候。

### 修正先進 `master`，再 cherry-pick 到 hotfix 分支

#17 最早寫的是反過來：在 hotfix 分支修好上線，之後再 cherry-pick 回 `master`。改成先進 `master`，理由：

- **回到 `master` 這一步不會被忘掉**。緊急的時候修好、上線、鬆一口氣，「之後再補回 `master`」最容易漏；
  漏了的話，下一次 `master` 上線就把 bug 帶回來。先進 `master` 的話，這件事在上線前就做完了。
- trunk-based 的參考資料都是這個方向：[trunkbaseddevelopment.com](https://trunkbaseddevelopment.com/branch-for-release/) 寫的是在 trunk 重現、修好、CI 驗過，再 cherry-pick 到 release 分支，
  只有 trunk 上重現不了時才反過來；Paul Hammant 2026 年 6 月的 [The cherry-pick asymmetry](https://paulhammant.com/2026/06/22/the-cherry-pick-asymmetry/) 也主張修正只從 trunk 流向 release 分支，
  理由同樣是回補很容易忘，並且建議 cherry-pick 要快、修正要小、分支要短命。
- **代價**：情境 B 多跑一次 CI（`master` 的 PR 一次、hotfix 分支一次），多約 2 分半。為了這 2 分半去冒「修正沒回到 `master`」的險不划算。

### 不開「緊急時跳過 CI」的開關

- CI 在 GitHub 上約 2 分 20 秒，加上合併後 `master` 那次和 watcher，情境 A 從合併到上線約 10 分鐘內。
  跳過 CI 省下的這幾分鐘，比不上修 A 壞 B、出第二次事故的代價 —— 緊急時人最累，最需要機器把關。
- OneUptime 2026 年 8 月的 [Break-glass infrastructure changes](https://oneuptime.com/blog/post/2026-08-05-break-glass-infrastructure-changes/view) 主張緊急程序要事先定好啟動條件，「這很急」本身不構成理由。
  talkcal 沒有定任何可以跳過 CI 的條件，所以就是不跳過。
- `master` 的 ruleset 本來就讓管理員也跳不過（[#17 Branch protection](https://github.com/Anderson-chen/talkcal/issues/17)）。這份流程是在這個前提下，讓緊急的時候也走得通。

### hotfix 分支的 CI 用手動觸發，不加 push 觸發

hotfix 分支不發 PR（見第 3 步），push 又只觸發 `master`，所以要另外讓它跑 CI。考慮過兩種：

| | 在 ci.yml 的 push 加 `hotfix/**` | **用 ci.yml 本來就有的 `workflow_dispatch`（選這個）** |
|---|---|---|
| ci.yml | 改觸發條件 | 不改，只在註解寫明 hotfix 用它 |
| 怎麼觸發 | push 就跑 | 多下一行 `gh workflow run` |
| 忘了觸發 | 不會忘 | 第 4 步等不到綠燈，部署前就會發現 |
| 紀錄 | Actions 裡事件是 `push` | 事件是 `workflow_dispatch`，還記下是誰觸發的 |
| 跑的內容 | checks、secrets、vulns | 一樣 |

情境 B 很少發生，一年可能用不到一次，不值得為它改 CI 的觸發條件；不動 ci.yml 也少跟其他改 CI 的 PR 衝突。
（第一版是加 push 觸發，2026-10-20 改成這樣）

## 證明手動觸發走得通

2026-10-20 用一條暫時的分支 `hotfix/26-dispatch-check`（跟這次 PR 同一個 commit `e9818e5`）驗證，驗完已刪除：

| 檢查 | 結果 |
|---|---|
| `gh workflow run ci.yml --ref hotfix/26-dispatch-check` 會跑完整 CI | 會：[run 39364685273](https://github.com/Anderson-chen/talkcal/actions/runs/39364685273)，事件是 `workflow_dispatch`，checks、secrets、vulns 三個 job 都跑。checks、vulns 綠燈；secrets 紅燈，掃到的是別條還沒合併的分支上的 commit（`claude/51-…`、`claude/54-…`），不在這條分支的歷史裡，2026-10-19 起所有 PR 的 secrets 都是這樣紅 |
| 反例：只 push 不會觸發 | `gh run list --workflow ci.yml --branch hotfix/26-dispatch-check --event push` 是 0 筆 |
| 反例：hotfix 分支的綠燈不會被自動部署 | watcher 的查詢（`--branch master --event push --status success`）回的是 `master` 的 commit，不是 `e9818e5` |

## 還沒做的

- hotfix 分支沒有 ruleset：force push、CI 沒綠就部署，都只靠照著這份文件做。一個人的專案先接受；之後可以在 GitHub 上對 `hotfix/**` 加一條 ruleset
- 沒有 feature flag：止血順序的第 2 步不存在，半成品也只能靠 revert 擋在線上之外。要不要做還沒決定
- 情境 B 的部署是手動的。[#25](https://github.com/Anderson-chen/talkcal/issues/25)（版本 tag）、[#19](https://github.com/Anderson-chen/talkcal/issues/19)（rollback）、[#24](https://github.com/Anderson-chen/talkcal/issues/24)（staging）合併後，
  「查線上版本」「暫停自動部署」「部署指定版本」都會有正式的做法，這份文件要跟著改（[#178](https://github.com/Anderson-chen/talkcal/issues/178)）
- 沒有 staging：hotfix 唯一的上線前驗證是 CI
