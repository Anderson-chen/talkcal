# Hotfix 流程

線上出問題、要緊急修正時怎麼做。一句話：**走平常那條路，只是插隊**。不跳過 CI，不直推 `master`，也照樣開 issue。

（[#26](https://github.com/Anderson-chen/talkcal/issues/26)，2026-10-16 寫下。分支策略、CI、CD 本身見 [docs/architecture.md](architecture.md#cicd從-commit-到線上) 和 [cd/README.md](../cd/README.md)）

## 先判斷走哪一條

| | 情境 A：`master` 可以直接上線（預設） | 情境 B：`master` 上有還不能上線的東西 |
|---|---|---|
| 什麼時候 | 線上跑的就是 `master` 最新的綠燈版本，或中間多出來的 commit 都可以一起上 | `master` 比線上新，而且多出來的東西不能上（例如最新那次部署失敗了、某個改動上線會出事） |
| 從哪裡開分支 | `master` | **線上那一版**的 commit |
| 怎麼部署 | 合併後 watcher 自己部署（跟平常一樣） | 先暫停 watcher，手動部署 hotfix 分支 |
| 修正怎麼回到 `master` | 本來就在 `master` | 修正先進 `master`，再 cherry-pick 到 hotfix 分支 |

在 talkcal，本機的 CD 會把**每一個**綠燈的 `master` commit 自動部署（[cd/README.md](../cd/README.md#本機-cdmaster-綠燈就自動部署)），
所以 `master` 幾乎就等於線上。情境 B 只會在這幾種時候出現：

- 最新的 `master` 部署失敗：watcher 同一個 commit 只試一次，線上停在上一版，而 `master` 最新那個已知是壞的
- watcher 被暫停了（`Disable-ScheduledTask`），`master` 已經往前走
- EC2：它是手動部署的，平常關機，線上可能比 `master` 舊很多

不確定的話，先查線上跑的是哪個 commit（見[情境 B 第 1 步](#1-找出線上跑的是哪個-commit)），跟 `master` 比：

```bash
git fetch origin
git log --oneline <線上的 sha>..origin/master   # 列出來的就是「master 有、線上沒有」的東西；每一個都能上線 → 情境 A
```

## 兩條都一樣的第 0 步：開 issue

```bash
gh issue create --project talkcal --label bug --title "線上：<症狀>" --body "<影響、從什麼時候開始、怎麼發現的>"
```

開完在看板上把 Status 設成 In Progress。緊急不等於不留紀錄：之後從 commit → PR → issue 點得回「當時發生什麼事」，
事後檢討的時間軸也從這裡開始算。

如果有現成的止血手段（關掉某個功能、退回上一版），先止血再修。退版本現在還沒有正式做法（[#19](https://github.com/Anderson-chen/talkcal/issues/19)）。

## 情境 A：`master` 可以直接上線

跟平常改程式一模一樣：

```bash
git switch -c <issue 編號>-hotfix-<描述> origin/master
# 修正 + 一個重現這個 bug 的測試（測試跟修正放同一個 commit）
git commit -m "[fix] <描述>（#<issue 編號>）"
git push -u origin HEAD                        # pre-push 在本機先跑一次 CI
gh pr create --fill --body "Closes #<issue 編號>"
gh pr checks --watch                           # 等 CI（約 2 分 20 秒）
gh pr merge --squash --delete-branch
```

合併後 `master` 再跑一次 CI，綠燈後 watcher 最多 5 分鐘內來拉。等不及的話立刻叫它跑一輪：

```powershell
Start-ScheduledTask talkcal-cd-local
```

EC2：`master` 綠燈後用 SSM 連進去重跑 `sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh`
（[infra/envs/1-ec2](../infra/envs/1-ec2/README.md)）。

確認上線：GitHub repo 頁面的 Deployments → `local` 最新一筆是這個 commit、狀態 success，再實際打一次出問題的功能。

## 情境 B：`master` 上有還不能上線的東西

### 1. 找出線上跑的是哪個 commit

**之後**（[#25](https://github.com/Anderson-chen/talkcal/issues/25) 版本與 release tag 做完）：直接用線上那一版的 tag。

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
之後也會把第 5 步手動部署的 hotfix 蓋掉。

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

名稱一定要是 `hotfix/` 開頭：CI 的 push 觸發只認 `master` 和 `hotfix/**`（[ci.yml](../.github/workflows/ci.yml)）。
這條分支不發 PR —— 要發也只能發到 `master`，diff 會是「線上到 `master` 之間的所有東西」反過來，沒有意義。

cherry-pick 有衝突代表線上那一版跟 `master` 已經分得很開：照衝突的內容手動改，改的東西要在第 4 步的 CI 裡被測到。

### 4. 等 hotfix 分支的 CI 綠燈

```bash
gh run list --workflow ci.yml --branch hotfix/<issue 編號>-<描述> --limit 1
gh run watch <上面的 run id> --exit-status     # 三個 job（checks、secrets、vulns）都要綠
```

hotfix 分支沒有 branch protection 擋「CI 沒綠就部署」，這一關靠人。CI 紅了就跟平常一樣修到綠，**不部署紅燈的版本**。

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

部署成功後刪掉 hotfix 分支：`git push origin --delete hotfix/<issue 編號>-<描述>`。它只活到 `master` 重新上線為止。

## 事後

| 什麼 | 怎麼做 |
|---|---|
| issue | 修正的 PR 寫 `Closes #N`，合併時自動關閉；情境 B 在 issue 留言寫上線的 sha 和時間 |
| 回歸測試 | 修正的 PR 裡一定要有重現這個 bug 的測試，不然下次改動又會壞回去 |
| 事後檢討 | 有使用者受到影響、或花了超過一小時，就寫一份到 [docs/postmortems/](postmortems/)，格式照 [2026-10-12 那份](postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)：blameless、時間軸、為什麼沒被擋下來、後續 issue |
| 後續 | 檢討裡找到的「為什麼 CI 沒抓到」各開一張 issue |

## 為什麼這樣選

### 修正先進 `master`，再 cherry-pick 到 hotfix 分支

#17 最早寫的是反過來：在 hotfix 分支修好上線，之後再 cherry-pick 回 `master`。改成先進 `master`，理由：

- **回到 `master` 這一步不會被忘掉**。緊急的時候修好、上線、鬆一口氣，「之後再補回 `master`」最容易漏；
  漏了的話，下一次 `master` 上線就把 bug 帶回來。先進 `master` 的話，這件事在上線前就做完了。
- trunk-based 的參考資料都是這個方向：[trunkbaseddevelopment.com](https://trunkbaseddevelopment.com/branch-for-release/) 寫的是在 trunk 重現、修好、CI 驗過，再 cherry-pick 到 release 分支，
  只有 trunk 上重現不了時才反過來；Paul Hammant 2026 年 6 月的 [The cherry-pick asymmetry](https://paulhammant.com/2026/06/22/the-cherry-pick-asymmetry/) 也主張修正只從 trunk 流向 release 分支，
  理由同樣是回補很容易忘，並且建議 cherry-pick 要快、修正要小、分支要短命。
- **代價**：情境 B 多跑一次 CI（`master` 的 PR 一次、hotfix 分支一次），多約 2 分半。為了這 2 分半去冒「修正沒回到 `master`」的險不划算。

### 不開「緊急時跳過 CI」的開關

- CI 在 GitHub 上約 2 分 20 秒，加上合併後 `master` 那次和 watcher 的輪詢，情境 A 從合併到上線約 10 分鐘內。
  這個速度不值得為了省它去冒「修 A 壞 B」的險 —— 緊急時人最累，最需要機器把關。
- OneUptime 2026 年 8 月的 [Break-glass infrastructure changes](https://oneuptime.com/blog/post/2026-08-05-break-glass-infrastructure-changes/view) 主張緊急程序要事先定好啟動條件，「這很急」本身不構成理由。
  talkcal 沒有定任何可以跳過 CI 的條件，所以就是不跳過。
- `master` 的 ruleset 本來就讓管理員也跳不過（[#17 Branch protection](https://github.com/Anderson-chen/talkcal/issues/17)）。這份流程是在這個前提下，讓緊急的時候也走得通。

### 從線上那一版開分支，而不是從 `master` 挑掉不能上的東西

把不能上線的 commit revert 掉也是一種做法，但那會改到 `master` 的歷史、之後還要 revert 回來，而且要確定挑乾淨了。
從線上那一版開分支，上線的內容就是「已經在線上驗過的東西 + 這一個修正」，風險最小、最好說明。

### hotfix 分支用 push 觸發 CI，不用 PR

發 PR 一定要有目標分支，hotfix 分支能發的只有 `master`，而它的 diff 對 `master` 沒有意義（見第 3 步）。
所以 [ci.yml](../.github/workflows/ci.yml) 的 push 觸發多加了 `hotfix/**`，push 上去就跑跟 PR 完全一樣的三個 job。
只加 `hotfix/**`、不改成所有分支：一般分支已經有 PR 那次的 CI，每個分支 push 都再跑一次只是重複。

### 不選 Git Flow 的 hotfix 分支

Git Flow 的 `hotfix/*` 從 `main` 開、合回 `main` 和 `develop`，是給「同時維護好幾個發行版」用的。talkcal 只有線上那一版，
所以只借它的名字當 CI 觸發條件，分支本身是情境 B 才開、上線後就刪的短命分支（分支策略見 #17）。

## 證明 CI 的觸發條件對

2026-10-16 用一條暫時的分支 `hotfix/26-ci-trigger-check`（跟這次 PR 同一個 commit `0edd983`）驗證，驗完已刪除：

| 檢查 | 結果 |
|---|---|
| push 到 `hotfix/26-ci-trigger-check` 會觸發 CI | 會：[run 38860826384](https://github.com/Anderson-chen/talkcal/actions/runs/38860826384)，事件是 `push`，三個 job 都跑 |
| 反例：一般分支 push 不會多跑一次 | `gh run list --workflow ci.yml --branch claude/26-hotfix-flow-877059 --event push` 是 0 筆，只有 PR 那次 |
| 反例：hotfix 分支的綠燈不會被自動部署 | watcher 的查詢（`--branch master --event push --status success`）回的是 `master` 的 commit，不是 `0edd983` |

## 還沒做的

- hotfix 分支沒有 ruleset：force push、CI 沒綠就部署，都只靠照著這份文件做。一個人的專案先接受；之後可以在 GitHub 上對 `hotfix/**` 加一條 ruleset（禁止 force push 和刪除以外的）
- 情境 B 的部署是手動的。等 [#25](https://github.com/Anderson-chen/talkcal/issues/25) 有了版本 tag、[#19](https://github.com/Anderson-chen/talkcal/issues/19) 有了 rollback，
  「部署某個指定版本」會變成正式的指令，第 5 步就換成那個
- 沒有 staging（[#24](https://github.com/Anderson-chen/talkcal/issues/24)）：hotfix 唯一的上線前驗證是 CI
