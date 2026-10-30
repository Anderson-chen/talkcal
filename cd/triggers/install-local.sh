#!/usr/bin/env bash
# macOS、Linux 版的 install-local.ps1：用 crontab 每幾分鐘在「這個 clone」跑一輪 watch.sh local（#49）。
# 在部署用的 clone 裡跑（不是開發用的那份）：
#
#   bash cd/triggers/install-local.sh              # 每 5 分鐘
#   bash cd/triggers/install-local.sh 2            # 每 2 分鐘
#   crontab -l | grep -v talkcal-cd-local | crontab -   # 拿掉
#
# 跟 Windows 版做同樣的兩件事：
#   1. 把 watch.sh 複製到 .git/cd-watch.sh，排程跑這份副本（理由見 watch.sh 開頭）
#   2. 登記排程。cron 沒有工作排程的 IgnoreNew，上一輪還在 build 時新的一輪由 watch.sh 自己的鎖擋下
set -euo pipefail

minutes="${1:-5}"
clone="$(cd "$(dirname "$0")/../.." && pwd)"
runner="$clone/.git/cd-watch.sh"
cp "$clone/cd/triggers/watch.sh" "$runner"

# cron 的 PATH 只有 /usr/bin:/bin，找不到 docker、gh（Homebrew 裝在 /opt/homebrew/bin）。把現在的 PATH 帶進去
line="*/$minutes * * * * PATH=\"$PATH\" bash \"$runner\" local >/dev/null 2>&1 # talkcal-cd-local"
# 先拿掉舊的那行再加，重跑不會疊出兩條；watch.sh 自己寫 .git/cd-local.log，cron 的輸出不用留
{ crontab -l 2>/dev/null | grep -v '# talkcal-cd-local$' || true; echo "$line"; } | crontab -

echo "已登記 talkcal-cd-local：每 $minutes 分鐘跑 $runner local"
echo "log：$clone/.git/cd-local.log"
