#!/usr/bin/env bash
# 在 EC2 上跑：把指定版本部署起來。第一次由 user_data 呼叫，之後要更新就手動重跑：
#
#   aws ssm start-session --target <instance-id> ...
#   sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
#   sudo GIT_REF=some-branch bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh   # 部署別的分支
#
# 做的事跟本機一樣是兩組 compose，只多了三步：拉指定版本、從 S3 拉模型、從 SSM 拉 .env。
set -euo pipefail

# user_data 寫好的設定；環境變數有給的話優先（例如臨時部署別的分支）
DEPLOY_ENV=/opt/talkcal/deploy.env
if [ -f "$DEPLOY_ENV" ]; then
  override_ref="${GIT_REF:-}"
  # shellcheck source=/dev/null
  source "$DEPLOY_ENV"
  GIT_REF="${override_ref:-$GIT_REF}"
fi
: "${PROJECT:?}" "${AWS_REGION:?}" "${GIT_REF:?}" "${MODELS_BUCKET:?}" "${DOTENV_PARAM:?}"

ROOT="/opt/$PROJECT"
REPO="$ROOT/repo"
MODELS_DIR="$ROOT/models"

echo "── 1. 原始碼：$GIT_REF"
# 正在跑的這支腳本也會被換掉：git 是刪掉舊檔、寫新檔，bash 手上拿的還是舊檔，不會讀到一半變內容。
# 新版的腳本從下次部署開始生效。
git -C "$REPO" fetch --depth 1 origin "$GIT_REF"
git -C "$REPO" checkout --detach FETCH_HEAD
git -C "$REPO" log -1 --oneline

echo "── 2. 模型檔：s3://$MODELS_BUCKET → $MODELS_DIR"
# sync 只傳有變的檔案，模型沒換時幾秒就結束。
# 接下來 model-seed 照本機的流程驗 SHA256（有上傳 SHA256SUMS 的話）、複製進 volume。
mkdir -p "$MODELS_DIR"
aws s3 sync "s3://$MODELS_BUCKET/" "$MODELS_DIR/" --region "$AWS_REGION" --no-progress
if ! ls "$MODELS_DIR"/*.gguf >/dev/null 2>&1; then
  echo "S3 裡沒有 .gguf 模型檔。先上傳：aws s3 cp <模型>.gguf s3://$MODELS_BUCKET/" >&2
  exit 1
fi

echo "── 3. .env：SSM $DOTENV_PARAM"
# compose 讀 deploy/.env（compose 檔所在的資料夾）。只給 root 讀：裡面有 DB 密碼。
umask 077
aws ssm get-parameter --name "$DOTENV_PARAM" --with-decryption \
  --region "$AWS_REGION" --query Parameter.Value --output text > "$REPO/deploy/.env"
umask 022

cd "$REPO"

echo "── 4. 服務（deploy/ + 雲端的差異 compose.ec2.yaml）"
# 第二個 -f 疊在第一個上面：同名欄位以後面的為準，本機的 compose.yaml 一行都不用改
docker compose \
  -f deploy/compose.yaml \
  -f infra/envs/1-ec2/compose.ec2.yaml \
  up -d --build --remove-orphans

echo "── 5. 觀測（ops/，跟本機同一份）"
# 要在 deploy/ 之後：Alloy 要加入的網路是 deploy/ 建的
docker compose -f ops/compose.yaml up -d

docker compose -f deploy/compose.yaml -f infra/envs/1-ec2/compose.ec2.yaml ps
echo "完成。網址：https://$(grep '^SITE_ADDRESS=' deploy/.env | cut -d= -f2)"
