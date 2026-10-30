#!/usr/bin/env bash
# 把 deploy/ 要用的模型檔下載到 MODELS_DIR，逐檔驗 SHA256。Windows（Git Bash）、macOS、Linux 都用這一支。
#
#   bash deploy/models/download.sh                   # 下載到 deploy/.env 的 MODELS_DIR
#   bash deploy/models/download.sh ~/llm/models      # 或直接給資料夾
#
# 清單在同一層的 manifest.txt（檔名、網址、SHA256）。做的事：
#   1. 已經有、而且 SHA256 對得上 → 跳過（重跑不會重新下載 5GB）
#   2. 下載到 <檔名>.part，中斷了重跑會從斷掉的地方接著下（curl -C -）
#   3. 驗 SHA256，對不上就刪掉 .part、失敗結束 —— 壞檔不會留在正式檔名上
#   4. 在資料夾裡寫 SHA256SUMS：model-seed 複製進 volume 時會拿它再驗一次（複製那一步也可能壞）
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# MANIFEST 可以換一份清單：驗證這支腳本本身時用小檔案（deploy/verify/clean-linux.sh），平常不用給
manifest="${MANIFEST:-$here/manifest.txt}"

# 資料夾：參數 > 環境變數 > deploy/.env，跟 compose 找 MODELS_DIR 的順序一樣（compose 沒有參數這一層）
dest="${1:-${MODELS_DIR:-}}"
if [ -z "$dest" ] && [ -f "$here/../.env" ]; then
  # 取最後一個 MODELS_DIR=，去掉引號和 Windows 存檔可能留下的 \r
  dest="$(sed -n 's/^MODELS_DIR=//p' "$here/../.env" | tail -n 1 | tr -d "\"'\r")"
fi
if [ -z "$dest" ]; then
  echo "不知道要下載到哪：給一個資料夾（bash deploy/models/download.sh <資料夾>），或先在 deploy/.env 填 MODELS_DIR" >&2
  exit 2
fi

# Linux、Git Bash 有 sha256sum；macOS 預設只有 shasum
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

mkdir -p "$dest"
echo "下載到 $dest"

grep -v -e '^#' -e '^[[:space:]]*$' "$manifest" | while read -r sum name url; do
  file="$dest/$name"
  if [ -f "$file" ]; then
    echo "驗證  $name（已經有了）..."
    if [ "$(sha256 "$file")" = "$sum" ]; then
      echo "ok    $name"
    else
      # 不自動覆蓋：可能是你自己換過的檔案。刪掉或改名之後重跑
      echo "$file 已存在但 SHA256 跟清單不同：確認之後刪掉它再重跑" >&2
      exit 1
    fi
  else
    echo "下載  $name ← $url"
    curl -fL --progress-bar --retry 3 -C - -o "$file.part" "$url"
    echo "驗證  $name ..."
    actual="$(sha256 "$file.part")"
    if [ "$actual" != "$sum" ]; then
      echo "SHA256 不符：清單 $sum，下載到的 $actual。已刪掉 $file.part，重跑一次；一直不符就是上游換了檔案" >&2
      rm -f "$file.part"
      exit 1
    fi
    mv "$file.part" "$file"
    echo "ok    $name"
  fi

  # SHA256SUMS：換掉這個檔名的舊行、保留其他檔案的（資料夾裡可能還有你自己放的模型）
  sums="$dest/SHA256SUMS"
  { [ -f "$sums" ] && grep -v "  $name\$" "$sums" || true; echo "$sum  $name"; } > "$sums.tmp"
  mv "$sums.tmp" "$sums"
done

echo "完成。deploy/.env 的 MODELS_DIR 要指到 $dest"
