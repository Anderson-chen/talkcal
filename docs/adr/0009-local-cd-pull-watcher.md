# 0009. 本機 CD 用 pull：watcher 自己去看 master 的 CI，綠燈才部署

- 狀態：已採用
- 日期：2026-10-13
- 相關：[24416d6 本機 CD](https://github.com/Anderson-chen/talkcal/commit/24416d6)、[150d9b1 部署步驟收進 cd/apply.sh](https://github.com/Anderson-chen/talkcal/commit/150d9b1)、epic [#17](https://github.com/Anderson-chen/talkcal/issues/17)

## 背景

master 合併、CI 綠燈之後要自動部署到本機（在本機模擬雲端部署），之後也要部署到 EC2。
GitHub 不該有連進自己電腦的權限。

## 選項

| | Push：GitHub Actions → OIDC → SSM 連進機器 | Pull：機器自己去看 |
|---|---|---|
| 外部能不能對機器下指令 | 能，靠 IAM 和自訂 SSM Document 收窄 | 不能，沒有進來的通道 |
| GitHub 要拿到什麼權限 | AWS 的 role | 不用；反過來是機器拿 GitHub 的 token（讀 CI、寫部署紀錄） |
| 本機和 AWS 一致 | 本機做不到，兩套流程 | 同一支 watcher，只差 env |
| 延遲 | 合併完馬上部署 | 最多一個輪詢間隔 |
| watcher 自己掛掉 | Actions 會紅 | 沒人知道 |

另一個選項是自架 Jenkins：CI 已經在 GitHub Actions，多一台 Jenkins 只是多一台要更新、修補、備份的伺服器。

## 決定

**`cd/triggers/watch.sh` 每 5 分鐘找 master 最新的綠燈 commit，沒試過就 checkout 並跑那個 commit 的 `cd/apply.sh`，結果寫回 GitHub Deployments。同一個 commit 只試一次。**

「做什麼」只寫一次：部署步驟在 `cd/apply.sh`，入口（watcher、Gradle 的 deploy、手動）可以很多個 —— 跟 CI 同一個原則（[0008](0008-ci-same-script-in-container.md)）。
`apply.sh` 一次換一個服務、都帶 `--no-deps`，部署 app 不會順便重啟模型。

## 代價

- 合併後最多等一個輪詢間隔。
- watcher 自己掛掉沒人知道：沒有「多久沒部署了」的告警，決定不做（[cd/README.md](../../cd/README.md) 的「已知限制，決定不做」）。
- 部署的詳細 log 在機器上，GitHub 只看得到成功或失敗。

## 什麼時候重想

- 上 EKS（[#58](https://github.com/Anderson-chen/talkcal/issues/58)）：同一個 pull 模型就是 Argo CD，要跟 GitHub Actions 推的做法比過再決定。
- 要合併即部署、或有多台機器要協調 → push 或 GitOps 控制器。

## 證據

- [cd/README.md](../../cd/README.md#為什麼是-pull)：push 和 pull 的對照、為什麼每個服務分開換
- [cd/triggers/watch.sh](../../cd/triggers/watch.sh)、[cd/apply.sh](../../cd/apply.sh)
- [docs/architecture.md 的 CI/CD 圖](../architecture.md#cicd從-commit-到線上)
