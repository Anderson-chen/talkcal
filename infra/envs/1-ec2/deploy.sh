#!/usr/bin/env bash
# 在 EC2 上把指定版本部署起來。第一次開機由 user_data 跑，之後要更新就連進機器手動重跑：
#
#   aws ssm start-session --target <instance-id> ...
#   sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
#   sudo GIT_REF=some-branch bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh   # 部署別的分支
#
# 跟本機一樣起 deploy/、ops/ 兩組 compose，多疊雲端的差異（compose.ec2.yaml、compose.<MODEL_BACKEND>.yaml），
# 另外多做這幾件事：從 SSM 拉 .env、gpu 時從 S3 拉模型、換新版前備份資料庫、裝備份排程。
set -euo pipefail

# 設定由 user_data 寫好；GIT_REF 有從環境變數給的話以它為準
DEPLOY_ENV=/opt/talkcal/deploy.env
if [ -f "$DEPLOY_ENV" ]; then
  override_ref="${GIT_REF:-}"
  # shellcheck source=/dev/null
  source "$DEPLOY_ENV"
  GIT_REF="${override_ref:-$GIT_REF}"
fi
: "${PROJECT:?}" "${AWS_REGION:?}" "${GIT_REF:?}" "${DOTENV_PARAM:?}"
# 只有 gpu 時建的機器，deploy.env 裡沒有 MODEL_BACKEND
MODEL_BACKEND="${MODEL_BACKEND:-gpu}"

ROOT="/opt/$PROJECT"
REPO="$ROOT/repo"
MODELS_DIR="$ROOT/models"

echo "── 1. 原始碼：$GIT_REF"
# checkout 會換掉正在跑的這支腳本，但 git 是刪舊檔、寫新檔，bash 讀的還是舊檔，不會跑到一半換內容；新版下次部署才生效。
# --tags：連 release tag 一起拉，cd/version.sh 才認得出這個 commit 是不是某個 v<版本>。
# 淺 clone 就夠：version.sh 只看指著 HEAD 的 tag
git -C "$REPO" fetch --depth 1 --tags origin "$GIT_REF"
git -C "$REPO" checkout --detach FETCH_HEAD
git -C "$REPO" log -1 --oneline
APP_VERSION="$(bash "$REPO/cd/version.sh")"
GIT_COMMIT="$(git -C "$REPO" rev-parse HEAD)"
export APP_VERSION GIT_COMMIT
echo "版本：$APP_VERSION"

if [ "$MODEL_BACKEND" = gpu ]; then
  echo "── 2. 模型檔：s3://$MODELS_BUCKET → $MODELS_DIR"
  # SHA256 由之後的 model-seed 驗，這裡只同步
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
# 裡面有 DB 密碼，只給 root 讀
umask 077
aws ssm get-parameter --name "$DOTENV_PARAM" --with-decryption \
  --region "$AWS_REGION" --query Parameter.Value --output text > "$REPO/deploy/.env"
umask 022
# .env 是空的、行尾帶 \r、少了 SMTP 收件人，在起任何容器之前就擋下並說出是哪一項。
# 不擋的話要等 app 起不來、--wait 判 unhealthy 才失敗，看不出原因
bash "$REPO/infra/envs/1-ec2/smoke.sh" env "$REPO/deploy/.env"

cd "$REPO"

compose_files=(
  -f deploy/compose.yaml
  -f infra/envs/1-ec2/compose.ec2.yaml
  -f "infra/envs/1-ec2/compose.$MODEL_BACKEND.yaml"
)
# 必要的 key 讓 compose 自己查（compose 檔裡的 ${X:?}），不另外維護清單。config 不起容器
docker compose "${compose_files[@]}" config -q

echo "── 4. 服務（deploy/ + compose.ec2.yaml + compose.$MODEL_BACKEND.yaml）"
# 映像檔先準備好：這一段只拉、只 build，registry 連不到或 build 失敗時，在跑的容器都還沒被換掉。
# 拉新的 digest、基底重拉，是為了跟 CI 的漏洞掃描（.github/workflows/ci.yml 的 --pull）掃到的一樣新
docker compose "${compose_files[@]}" pull --quiet --ignore-buildable  # 第三方：同一個 tag 官方重新 build 過，就換成新的 digest
docker compose --env-file deploy/.env -f ops/compose.yaml -f infra/envs/1-ec2/compose.ops.yaml pull --quiet
docker compose "${compose_files[@]}" build --pull
# web、postgres 的 Dockerfile 有 apk upgrade，但那一層會被快取：Dockerfile 和基底都沒變時，套件停在第一次 build 那天。
# 只在還有套件能升時不用快取重 build；每次都不用快取的話，映像檔每次都變，postgres 每次部署都要重啟
for svc in web postgres; do
  # config --images 會連依賴的服務一起列（web 會多出 app、postgres）
  image="$(docker compose "${compose_files[@]}" config --images "$svc" | grep "^talkcal/$svc:")" \
    || { echo "compose 裡找不到 talkcal/$svc:<tag> 的映像檔" >&2; exit 1; }
  pending="$(docker run --rm --entrypoint sh "$image" -c 'apk update -q >/dev/null && apk version -l "<"')"
  if grep ' < ' <<<"$pending"; then
    echo "$svc 的映像檔還有上面這些套件能升，不用快取重 build"
    docker compose "${compose_files[@]}" build --no-cache "$svc"
  fi
done

# postgres 只在第一次初始化時讀 POSTGRES_PASSWORD。Terraform 換了密碼的話，app 拿新的、資料庫還是舊的，app 就連不上，
# 所以先只起 postgres，把密碼設成 .env 這一份，再起 app。密碼沒換時就是設成同一組
docker compose "${compose_files[@]}" up -d --wait postgres
# 走 local socket（官方映像檔設成 trust），不用知道舊密碼。密碼從 stdin 送：放在指令參數的話 ps 看得到。
# 密碼只有英數字（secrets.tf 的 special = false），直接放進 SQL 字串不用跳脫
db_password="$(grep '^DB_PASSWORD=' deploy/.env | cut -d= -f2-)"
printf "ALTER ROLE talkcal PASSWORD '%s';\n" "$db_password" \
  | docker compose "${compose_files[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U talkcal -d talkcal
unset db_password

# 新版的 Flyway migration 把資料改壞時，回得去部署前那一刻。備份失敗就停在這裡，不換新版
bash infra/envs/1-ec2/backup-to-s3.sh pre-deploy

docker compose "${compose_files[@]}" up -d --remove-orphans

echo "── 5. 觀測（ops/ + compose.ops.yaml）"
# 要在 deploy/ 之後：Alloy 要加入 deploy/ 建的網路。
# --env-file：Grafana 的 SMTP 設定和 heartbeat 網址在 deploy/.env；compose 預設只讀 ops/ 底下的 .env
docker compose --env-file deploy/.env -f ops/compose.yaml -f infra/envs/1-ec2/compose.ops.yaml up -d

echo "── 6. 備份排程（#45）：每小時一次、關機前一次"
# 裝在這裡不裝在 user_data：user_data 只在第一次開機跑，已經在跑的機器拿不到改過的排程。每次部署都覆寫
cat > "/etc/systemd/system/$PROJECT-backup.service" <<UNIT
[Unit]
Description=$PROJECT: back up PostgreSQL to S3
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/bin/bash $REPO/infra/envs/1-ec2/backup-to-s3.sh hourly
UNIT
cat > "/etc/systemd/system/$PROJECT-backup.timer" <<UNIT
[Unit]
Description=$PROJECT: hourly PostgreSQL backup

[Timer]
OnCalendar=hourly
# 關機期間錯過的那幾次不補：關機時本來就沒有新資料，關機前那份已經在 S3 了
Persistent=false

[Install]
WantedBy=timers.target
UNIT
# 開機時 ExecStart 什麼都不做，關機時 systemd 停這個 service，跑的是 ExecStop。
# 關機的順序跟開機相反，After=docker.service 讓它在 Docker（和裡面的 postgres）停掉之前跑
cat > "/etc/systemd/system/$PROJECT-backup-on-shutdown.service" <<UNIT
[Unit]
Description=$PROJECT: back up PostgreSQL to S3 before shutdown
After=docker.service containerd.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/true
ExecStop=/bin/bash $REPO/infra/envs/1-ec2/backup-to-s3.sh shutdown
TimeoutStopSec=5min

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now "$PROJECT-backup.timer" "$PROJECT-backup-on-shutdown.service"
systemctl list-timers "$PROJECT-backup.timer" --no-pager

docker compose "${compose_files[@]}" ps

# 根磁碟跟 PostgreSQL 共用，重拉、重 build 換下來的舊映像檔要清掉。
# 不帶 -a：退版要用的映像檔不能清，見 cd/rollback.md「映像檔不在了」
docker image prune -f

echo "── 7. 冒煙測試：網站、觀測、Bedrock、告警寄信"
# --wait 只證明 healthcheck 過了；smoke.sh 從外面走一遍，沒過就讓部署失敗
bash infra/envs/1-ec2/smoke.sh services deploy/.env

echo "完成。網址：https://$(grep '^SITE_ADDRESS=' deploy/.env | cut -d= -f2)"
