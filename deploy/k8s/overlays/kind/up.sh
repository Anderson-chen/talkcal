#!/usr/bin/env bash
# 在本機起一個 kind 叢集，把 talkcal 部署上去。跑第二次也可以：叢集已經在就只做部署。
#
# 先決條件：
#   - kind、kubectl 在 PATH 上（kubectl 1.27 以上，內建 kustomize）
#   - deploy/ 的 compose 在跑（至少 llm-chat）：叢集裡的 app 連那個模型容器（components/llm-chat-external）
#   - 映像檔已經 build 好：docker compose -f deploy/compose.yaml build app web
#   - deploy/.env 有 DB_PASSWORD（跟 compose 同一份，見 deploy/README.md）
#   - 觀測要看得到：ops/ 的 compose 也要在跑（Alloy 送進那組的 Mimir/Loki/Tempo）
#
# 用法：deploy/k8s/overlays/kind/up.sh        收掉：kind delete cluster --name talkcal
#   APP_TAG=<tag> WEB_TAG=<tag>  要部署哪一版的映像檔（預設 local，就是 compose build 出來的那個；版本號見 #25）
#   ENV_FILE=<路徑>              DB_PASSWORD 從哪讀（預設 deploy/.env；在 git worktree 裡跑時指到主資料夾那份）
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
cluster=talkcal
# 釘 node 映像檔的版本：kind 每一版預設的 k8s 版本不同，不釘的話換了 kind 版本叢集就跟著變
node_image="${KIND_NODE_IMAGE:-kindest/node:v1.31.1}"

# ── 1. 叢集 ───────────────────────────────────────────────────────────
if ! kind get clusters | grep -qx "$cluster"; then
  kind create cluster --config "$here/cluster.yaml" --image "$node_image" --wait 120s
fi
kubectl config use-context "kind-$cluster" >/dev/null

# ── 2. 接上 compose 的網路 ───────────────────────────────────────────
# kind 的節點本身是 Docker 容器。接上 deploy/ 和 ops/ 的網路以後，Pod 問叢集 DNS 找不到的名字（talkcal-llm-chat、
# talkcal-loki…）會往上問到 Docker 內建的 DNS，ExternalName 就指得過去。沒在跑的那組就跳過
for net in talkcal-deploy_default talkcal-observability_default; do
  if docker network inspect "$net" >/dev/null 2>&1; then
    for node in $(kind get nodes --name "$cluster"); do
      docker network connect "$net" "$node" 2>/dev/null || true   # 已經接過會報錯，忽略
    done
  else
    echo "略過 $net：那組 compose 沒在跑" >&2
  fi
done

# ── 3. 映像檔放進節點 ─────────────────────────────────────────────────
# kind 的節點看不到主機的 Docker 映像檔，也沒有 registry，要一個一個複製進去。
# 要部署的那一版先另外標成 :kind（overlay 的 images 寫的就是 :kind）：
# overlay 不必跟著版本改，也不會為了換版去蓋掉 compose 正在用的 :local
docker tag "talkcal/app:${APP_TAG:-local}" talkcal/app:kind
docker tag "talkcal/web:${WEB_TAG:-local}" talkcal/web:kind
#
# 不用 kind load docker-image：Docker Desktop 開了 containerd 映像檔儲存時，從 Docker Hub 拉的多平台映像檔
# （postgres、alloy）只有本機這個平台的層，kind load 帶 --all-platforms 匯入，會因為找不到別的平台的層而失敗
# （ctr: content digest ... not found，2026-10-16 實測）。改成自己 save 再匯入，只要 linux/amd64
for image in talkcal/app:kind talkcal/web:kind postgres:18.6-alpine grafana/alloy:v1.11.0; do
  for node in $(kind get nodes --name "$cluster"); do
    docker save "$image" | docker exec -i "$node" \
      ctr --namespace=k8s.io images import --platform linux/amd64 --snapshotter=overlayfs - >/dev/null
  done
  echo "已放進節點：$image"
done

# ── 4. 祕密 ───────────────────────────────────────────────────────────
# 從 deploy/.env 讀，直接建成 Secret，不寫成檔案、不進 git（跟 compose 讀同一份）
env_file="${ENV_FILE:-$repo/deploy/.env}"
db_password="$(grep -E '^DB_PASSWORD=' "$env_file" | cut -d= -f2- | tr -d '\r')"
[ -n "$db_password" ] || { echo "$env_file 沒有 DB_PASSWORD" >&2; exit 1; }
kubectl create namespace talkcal --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n talkcal create secret generic talkcal-db --from-literal=password="$db_password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# ── 5. 部署 ───────────────────────────────────────────────────────────
kubectl apply -k "$repo/ops/k8s/overlays/kind"
kubectl apply -k "$here"
kubectl -n talkcal rollout status statefulset/postgres --timeout=180s
kubectl -n talkcal rollout status deployment/app --timeout=300s
kubectl -n talkcal rollout status deployment/web --timeout=120s
kubectl -n talkcal-ops rollout status daemonset/alloy --timeout=120s

echo
echo "前端：http://127.0.0.1:38080   app：http://127.0.0.1:38090/actuator/health"
