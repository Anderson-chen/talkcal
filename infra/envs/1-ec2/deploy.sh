#!/usr/bin/env bash
# 在 EC2 上跑：把指定版本部署起來。第一次由 user_data 呼叫，之後要更新就手動重跑：
#
#   aws ssm start-session --target <instance-id> ...
#   sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
#   sudo GIT_REF=some-branch bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh   # 部署別的分支
#
# 做的事跟本機一樣是兩組 compose，只多了幾步：拉指定版本、（gpu）從 S3 拉模型、從 SSM 拉 .env。
# MODEL_BACKEND 決定多疊哪一份 compose：compose.bedrock.yaml（模型走 Bedrock）或 compose.gpu.yaml（自己跑）。
set -euo pipefail

# user_data 寫好的設定；環境變數有給的話優先（例如臨時部署別的分支）
DEPLOY_ENV=/opt/talkcal/deploy.env
if [ -f "$DEPLOY_ENV" ]; then
  override_ref="${GIT_REF:-}"
  # shellcheck source=/dev/null
  source "$DEPLOY_ENV"
  GIT_REF="${override_ref:-$GIT_REF}"
fi
: "${PROJECT:?}" "${AWS_REGION:?}" "${GIT_REF:?}" "${DOTENV_PARAM:?}"
# 舊版 user_data 寫的 deploy.env 沒有 MODEL_BACKEND：那時只有 gpu
MODEL_BACKEND="${MODEL_BACKEND:-gpu}"

ROOT="/opt/$PROJECT"
REPO="$ROOT/repo"
MODELS_DIR="$ROOT/models"

echo "── 1. 原始碼：$GIT_REF"
# 正在跑的這支腳本也會被換掉：git 是刪掉舊檔、寫新檔，bash 手上拿的還是舊檔，不會讀到一半變內容。
# 新版的腳本從下次部署開始生效。
git -C "$REPO" fetch --depth 1 origin "$GIT_REF"
git -C "$REPO" checkout --detach FETCH_HEAD
git -C "$REPO" log -1 --oneline

if [ "$MODEL_BACKEND" = gpu ]; then
  echo "── 2. 模型檔：s3://$MODELS_BUCKET → $MODELS_DIR"
  # sync 只傳有變的檔案，模型沒換時幾秒就結束。
  # 接下來 model-seed 照本機的流程驗 SHA256（有上傳 SHA256SUMS 的話）、複製進 volume。
  : "${MODELS_BUCKET:?}"
  mkdir -p "$MODELS_DIR"
  aws s3 sync "s3://$MODELS_BUCKET/" "$MODELS_DIR/" --region "$AWS_REGION" --no-progress
  if ! ls "$MODELS_DIR"/*.gguf >/dev/null 2>&1; then
    echo "S3 裡沒有 .gguf 模型檔。先上傳：aws s3 cp <模型>.gguf s3://$MODELS_BUCKET/" >&2
    exit 1
  fi
else
  echo "── 2. 模型：$MODEL_BACKEND，機器上不放模型檔"
fi

echo "── 3. .env：SSM $DOTENV_PARAM"
# compose 讀 deploy/.env（compose 檔所在的資料夾）。只給 root 讀：裡面有 DB 密碼。
umask 077
aws ssm get-parameter --name "$DOTENV_PARAM" --with-decryption \
  --region "$AWS_REGION" --query Parameter.Value --output text > "$REPO/deploy/.env"
umask 022
# 寫出空檔案卻回 0 的事真的發生過（snap 版的 aws，#74）。不擋的話要到第 4 步 compose 才報「缺 DB_PASSWORD」，看不出原因
if ! grep -q '^DB_PASSWORD=' "$REPO/deploy/.env"; then
  echo "deploy/.env 是空的或缺 DB_PASSWORD：檢查 aws --version、SSM 參數 $DOTENV_PARAM" >&2
  exit 1
fi

cd "$REPO"

# 後面的 -f 疊在前面上面：同名欄位以後面的為準，本機的 compose.yaml 一行都不用改
compose_files=(
  -f deploy/compose.yaml                            # 本機和雲端共用
  -f infra/envs/1-ec2/compose.ec2.yaml              # 雲端共用：DB 密碼、記憶體上限、Caddy
  -f "infra/envs/1-ec2/compose.$MODEL_BACKEND.yaml" # 模型從哪來
)

echo "── 4. 服務（deploy/ + compose.ec2.yaml + compose.$MODEL_BACKEND.yaml）"
# 先只起 postgres，把資料庫裡的密碼同步成 .env 這一份，app 才起來（#14）。
# 為什麼要這步：官方 postgres 映像檔只在「資料庫第一次初始化」時讀 POSTGRES_PASSWORD，之後就不管了。
# 不同步的話，在 Terraform 換了密碼再部署，app 拿新密碼、資料庫還是舊的 —— app 連不上，整個服務掛掉。
# 每次部署都跑：密碼沒換時就是設成同一組，沒有副作用。
docker compose "${compose_files[@]}" up -d --wait postgres
# 走容器裡的 local socket（官方映像檔對它是 trust），不必知道舊密碼也改得了。
# 密碼從 stdin 送進去，不放在指令參數裡：參數在 ps 看得到，stdin 看不到。
# 只用英數字（secrets.tf 的 special = false），所以直接放進 SQL 字串不會有跳脫問題
db_password="$(grep '^DB_PASSWORD=' deploy/.env | cut -d= -f2-)"
printf "ALTER ROLE talkcal PASSWORD '%s';\n" "$db_password" \
  | docker compose "${compose_files[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U talkcal -d talkcal
unset db_password

docker compose "${compose_files[@]}" up -d --build --remove-orphans

echo "── 5. 觀測（ops/ + compose.ops.yaml）"
# 要在 deploy/ 之後：Alloy 要加入的網路是 deploy/ 建的。
# 設定跟本機同一份，compose.ops.yaml 只蓋掉雲端不一樣的值（trace 取樣比例）
docker compose -f ops/compose.yaml -f infra/envs/1-ec2/compose.ops.yaml up -d

docker compose "${compose_files[@]}" ps
echo "完成。網址：https://$(grep '^SITE_ADDRESS=' deploy/.env | cut -d= -f2)"
