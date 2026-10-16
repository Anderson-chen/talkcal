# 0004. 部署由 GitHub Actions 推（`kubectl apply -k`），先不上 Argo CD

- 狀態：已採用
- 日期：2026-10-16
- 相關：[#58](https://github.com/Anderson-chen/talkcal/issues/58)、[#19 rollback](https://github.com/Anderson-chen/talkcal/issues/19)、[#20 canary](https://github.com/Anderson-chen/talkcal/issues/20)、[#25 版本 tag](https://github.com/Anderson-chen/talkcal/issues/25)

## 背景

映像檔要從 GitHub Actions 推到 ECR（用 OIDC 臨時憑證，不放長期 key），之後要有東西把新版本套到叢集上。
本機的 manifest 是 kustomize（#56），版本號由 `cd/version.sh` 產生（#25，PR #135）。
叢集平常是 destroy 掉的，展示前才 apply。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| GitHub Actions 推：`kustomize edit set image` → `kubectl apply -k` → `kubectl rollout status` | 元件最少；部署紀錄就在 Actions 的頁面；跟 CI 同一個地方 | CI 要有叢集權限（push 模式）；有人手動改了叢集，下次部署前不會被拉回來 |
| Argo CD（GitOps，pull） | 叢集自己去 git 拉、自動修正漂移；CI 不需要叢集權限；UI 好看 | 叢集裡多一組元件（約 1 GB 記憶體）；叢集每次重建都要重新裝、重新接 repo；單一叢集、偶爾部署時，自動修正漂移的好處很少 |
| Flux | 同上，比 Argo CD 輕 | 同上 |

2026 年的整理大多是：單一叢集、部署不頻繁時，先用 Actions 推，需要自動修正漂移或多叢集時再加 GitOps 控制器
（[markaicode：Argo CD vs GitHub Actions](https://markaicode.com/vs/argocd-vs-github-actions/)、[OneUptime 2026-02](https://oneuptime.com/blog/post/2026-02-26-gitops-argocd-vs-traditional-cicd/markdown)）。

## 決定

**`.github/workflows/eks-deploy.yml`，只能手動觸發（`workflow_dispatch`），跑在 GitHub environment `eks` 底下。**

- 認證：GitHub OIDC → IAM 角色 `talkcal-github-deploy`。trust policy 只認 `repo:Anderson-chen/talkcal:environment:eks`，
  所以別的分支、PR 的 workflow 拿不到；environment 還可以在 GitHub 設定要人按核准。
- 叢集權限壓到最小：EKS access entry 只把這個角色放進 `talkcal-deployers` 群組，RBAC 只在 `talkcal` namespace 綁 `edit`（加上 ExternalSecret、TargetGroupBinding 兩種 CRD）。
  拿不到 kube-system、改不了節點。
- 版本：映像檔 tag 用 `cd/version.sh`（#25）；ECR 設成 tag 不可覆寫，同一個版本號永遠是同一份映像檔。
- 回退（#19）：用舊的版本號重跑一次這個 workflow；canary（#20）在叢集上要另外選工具（Argo Rollouts 可以不裝 Argo CD 單獨用）。

## 代價

- CI 有叢集寫入權限（限一個 namespace）。
- 叢集的實際狀態可能跟 git 不一樣，直到下次部署。

## 什麼時候重想

- 多個叢集或多個環境（staging 搬上 EKS，#24）→ Argo CD 或 Flux。
- 叢集要常駐、會有人手動改 → 需要自動修正漂移。
