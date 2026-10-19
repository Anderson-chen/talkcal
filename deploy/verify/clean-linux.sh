#!/usr/bin/env bash
# 在一台「乾淨的 Linux」上照 README 從 0 起整套，證明不依賴作者那台 Windows（#49）。
#
#   bash deploy/verify/clean-linux.sh            # 驗目前的 HEAD（要先 commit：驗的是 git 裡的內容，不是工作目錄）
#   KEEP=1 bash deploy/verify/clean-linux.sh     # 跑完不刪容器，可以 docker exec 進去看
#
# 乾淨的 Linux = 一個新的 docker:dind 容器：Alpine、自己的 Docker daemon，沒有任何 image、volume、模型檔，
# 也看不到主機的 C:\ 或 deploy/.env。repo 用 git bundle 送進去再 clone——跟換一台電腦 git clone 一樣，
# 沒 commit 的改動、.gitignore 擋掉的檔案都不會跟進去。
# 進去之後跑 deploy/verify/from-zero.sh（README 的步驟＋反例）。
#
# 需要：Docker（Windows 的 Git Bash、macOS、Linux 都可以），磁碟約 15GB（模型 5GB 下載一份、播進 volume 再一份，加上映像檔）。
# 時間：模型下載看網速（7 MB/s 約 12 分鐘），build app、web 約 5 分鐘。
set -euo pipefail
# Git Bash 會把 /root/... 這種參數改寫成 C:/Program Files/Git/root/...，docker exec 進容器的路徑不能被改
export MSYS_NO_PATHCONV=1

name=talkcal-clean-linux
dind_image=docker:27.5.1-dind
root="$(git rev-parse --show-toplevel)"

bundle="$(mktemp)"
trap 'rm -f "$bundle"; [ -n "${KEEP:-}" ] || docker rm -f -v "$name" >/dev/null 2>&1 || true' EXIT
git -C "$root" bundle create -q "$bundle" HEAD
echo "── 驗證 $(git -C "$root" log -1 --format='%h %s')"

docker rm -f -v "$name" >/dev/null 2>&1 || true
# --privileged：dind 要自己管 cgroup、網路，這是 Docker 官方 dind 映像檔的用法
docker run -d --privileged --name "$name" "$dind_image" >/dev/null
for _ in $(seq 60); do docker exec "$name" docker info >/dev/null 2>&1 && break; sleep 1; done
docker exec "$name" docker info >/dev/null

# Alpine 預設沒有 bash、git、curl；sed、date 也換成 GNU 版，跟一般 Linux 發行版一樣
docker exec "$name" apk add --no-cache -q bash git curl coreutils sed
docker exec -i "$name" sh -c 'cat > /root/talkcal.bundle' < "$bundle"
docker exec "$name" git clone -q /root/talkcal.bundle /root/talkcal
docker exec -w /root/talkcal "$name" bash deploy/verify/from-zero.sh
