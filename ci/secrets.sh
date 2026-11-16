#!/usr/bin/env bash
# 用 gitleaks 掃 git 歷史裡有沒有祕密（API key、token、私鑰）。
#
#   ci/secrets.sh                            掃 HEAD 往回的整個歷史（等於 ci/secrets.sh HEAD）
#   ci/secrets.sh <commit> --not --remotes   只掃還沒推上去的 commit
#
# 參數原樣交給 gitleaks 的 --log-opts，也就是 git log 選範圍的語法。
# 為什麼要掃歷史、不放進 checks.sh、GitHub 有 push protection 還要這支，見 ci/README.md「祕密掃描」。
#
# 被誤判時（例如測試用的假 key）：那一行加註解 gitleaks:allow，或把報告裡的 Fingerprint 寫進 .gitleaksignore。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 別讓 Git Bash 把 /repo 改寫成 Windows 路徑（見 ci/run.sh）

# 版本和 digest 都釘死（理由見 ci/README.md「漏洞掃描」）
image=ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
repo=$(pwd -W 2>/dev/null || pwd)

# 沒給參數時補 HEAD：--log-opts 是空的話 gitleaks 會掃 git log --all。CI 用 fetch-depth: 0 checkout 時，
# 所有開著的 PR 分支都在 refs/remotes/ 底下，別人分支上一個誤判就讓每個 PR 都紅。
# 在 PR 上 HEAD 是 master 加這個 PR 的 merge commit，master 的歷史和這個 PR 的 commit 都掃得到。
# --redact：CI 的 log 是公開的，報告裡要把祕密遮起來
docker run --rm --volume "$repo:/repo:ro" "$image" \
    git /repo --log-opts="${*:-HEAD}" --redact --no-banner --verbose
