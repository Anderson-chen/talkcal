#!/usr/bin/env bash
# 掃 git 歷史裡有沒有祕密（API key、token、私鑰）。用 gitleaks，在容器裡跑。
#
#   ci/secrets.sh                       掃 HEAD 往回的整個歷史（GitHub Actions 用，等於 ci/secrets.sh HEAD）
#   ci/secrets.sh <commit> --not --remotes   只掃還沒推上去的 commit（pre-push hook 用）
#
# 參數原樣交給 git log（gitleaks 的 --log-opts），所以「掃哪些 commit」就是 git log 的語法。
#
# 為什麼沒帶參數時要補 HEAD：gitleaks 的 --log-opts 是空的話，會自己下 git log --all，
# 掃的是 repo 裡「所有」ref。CI 用 fetch-depth: 0 checkout 時，所有開著的 PR 的分支都在
# refs/remotes/origin/ 底下，結果別人分支上的一個誤判，會讓每一條 PR 都紅燈。
# 只掃 HEAD 能走到的 commit：在 PR 上 HEAD 是 master 加這個 PR 的 merge commit，
# 所以 master 的歷史和這個 PR 的每個 commit 都會掃到，別人的分支等它自己的 PR 去掃。
#
# 為什麼掃歷史，不是掃 checks.sh 那份 git archive：祕密在第 3 個 commit 加進去、第 4 個刪掉，
# 最新的檔案看起來乾淨，但推上去之後兩個 commit 都在，任何人 clone 都拿得到。
# 所以不放進 checks.sh —— 那邊的容器只有某一個 commit 的檔案，沒有 .git。
#
# 為什麼 GitHub 已經有 push protection 還要這支：
#   - GitHub 只認得各家服務的 key 格式（AWS、OpenAI…），這支多了通用的規則（例如寫死的 token）
#   - pre-push 在本機就擋，祕密根本沒離開這台電腦；GitHub 擋的時候已經送出去了
#   - 換到 GitLab、自架 Git 也照樣有效
#
# 被誤判時（例如測試用的假 key）：那一行加註解 gitleaks:allow，或把報告裡的 Fingerprint 寫進 .gitleaksignore。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 把 /repo 改寫成 Windows 路徑

# 版本和 digest 都釘死：掃描工具本身被換掉，等於整道關卡失效
image=ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
repo=$(pwd -W 2>/dev/null || pwd)

# --redact：報告裡把祕密遮起來。CI 的 log 是公開的，不能在說「這裡有 key」的同時把 key 印出來
docker run --rm --volume "$repo:/repo:ro" "$image" \
    git /repo --log-opts="${*:-HEAD}" --redact --no-banner --verbose
