#!/usr/bin/env bash
# 照 README「怎麼跑」從剛 clone 下來的 repo 把整套起起來，每一步都檢查結果；含兩個反例。
# 在 deploy/verify/clean-linux.sh 開的乾淨 Linux 容器裡跑（那邊會先 git clone，再在 repo 根目錄呼叫這支）。
# 也可以直接在一台沒裝過 talkcal 的 Linux / macOS 上跑：bash deploy/verify/from-zero.sh
#
# 跟 README 唯一的差別：沒有 GPU，所以多疊一份 compose.mock-llm.yaml，模型換成 mock（理由寫在那份檔案裡）。
# 模型檔照樣真的下載、驗 SHA256、播進 volume——只是沒有東西去「跑」它。
set -euo pipefail
cd "$(dirname "$0")/../.."

models_dir="${VERIFY_MODELS_DIR:-$HOME/llm/models}"
compose=(docker compose -f deploy/compose.yaml -f deploy/verify/compose.mock-llm.yaml)
base="http://localhost:18080"

step() { echo; echo "════ $*"; }
fail() { echo "✗ $*" >&2; exit 1; }
pass() { echo "✓ $*"; }

step "0. 環境"
uname -srm
grep PRETTY_NAME /etc/os-release 2>/dev/null || sw_vers 2>/dev/null || true
docker version --format 'Docker Engine {{.Server.Version}}'
docker compose version
git log -1 --format='commit %h %s'

step "1. cp deploy/.env.example deploy/.env，填 DB_PASSWORD 和歸檔的帳密，先不填 MODELS_DIR"
[ ! -f deploy/.env ] || fail "deploy/.env 已經存在：這支要從乾淨的 clone 開始"
cp deploy/.env.example deploy/.env
sed -i.bak "s|^DB_PASSWORD=.*|DB_PASSWORD=verify-$(date +%s)|" deploy/.env && rm deploy/.env.bak
# 歸檔（#54）的 RustFS 帳密也是必填：不填的話第 2 步會先報缺它，看不出 MODELS_DIR 有沒有被擋
sed -i.bak -e "s|^ARCHIVE_ACCESS_KEY=.*|ARCHIVE_ACCESS_KEY=verify|" -e "s|^ARCHIVE_SECRET_KEY=.*|ARCHIVE_SECRET_KEY=verify-$(date +%s)|" deploy/.env && rm deploy/.env.bak

step "2. 反例：MODELS_DIR 沒填 → compose 拒絕啟動，而且說得出缺什麼"
if out="$("${compose[@]}" up -d 2>&1)"; then
  fail "MODELS_DIR 沒填卻起得來：$out"
fi
echo "$out"
grep -q '缺 MODELS_DIR' <<<"$out" || fail "有失敗，但錯誤訊息沒說缺 MODELS_DIR"
[ -z "$(docker ps -aq)" ] || fail "失敗了卻留下容器：$(docker ps -a --format '{{.Names}}')"
pass "拒絕啟動、一個容器都沒建"

step "3. 填 MODELS_DIR=$models_dir"
sed -i.bak "s|^MODELS_DIR=.*|MODELS_DIR=$models_dir|" deploy/.env && rm deploy/.env.bak
mkdir -p "$models_dir"

step "4. 反例：MODELS_DIR 指到還沒下載的空資料夾 → model-seed 失敗，模型服務不會起"
if out="$("${compose[@]}" up -d --wait llm-chat 2>&1)"; then
  fail "資料夾是空的卻起得來：$out"
fi
echo "$out" | tail -n 3
seed_log="$("${compose[@]}" logs --no-log-prefix model-seed 2>&1)"
echo "$seed_log" | head -n 1
grep -q '沒有 Qwen3-8B-Q4_K_M.gguf' <<<"$seed_log" || fail "model-seed 失敗了，但 log 沒說缺哪個檔案"
llm_state="$("${compose[@]}" ps -a --format '{{.Service}} {{.State}}' | awk '$1=="llm-chat"{print $2}')"
[ "$llm_state" != running ] || fail "model-seed 失敗了，llm-chat 卻在跑"
pass "model-seed 說出缺哪個檔案，llm-chat 停在 ${llm_state:-沒建}"
"${compose[@]}" down -v >/dev/null 2>&1

step "5. bash deploy/models/download.sh（讀 deploy/.env 的 MODELS_DIR，約 5GB）"
start=$(date +%s)
bash deploy/models/download.sh
echo "下載＋驗證 $(( $(date +%s) - start )) 秒"
ls -la "$models_dir"

step "6. 起資料庫和模型（README：docker compose -f deploy/compose.yaml up -d postgres llm-chat）"
start=$(date +%s)
"${compose[@]}" up -d --wait --wait-timeout 900 postgres llm-chat
"${compose[@]}" logs --no-log-prefix model-seed
echo "播種＋起服務 $(( $(date +%s) - start )) 秒"

step "7. 部署 app 和前端（./gradlew deploy 呼叫的 cd/apply.sh 做的就是這兩行；這裡沒有 JDK，跳過它前面的單元測試）"
start=$(date +%s)
"${compose[@]}" up -d --build --no-deps --wait --wait-timeout 900 app
"${compose[@]}" up -d --build --no-deps --wait --wait-timeout 300 web
echo "build＋起 app、web $(( $(date +%s) - start )) 秒"
"${compose[@]}" ps

step "8. 從瀏覽器的入口（$base，nginx）走一遍：首頁 → 註冊 → 登入 → 跟助理說一句話"
jar="$(mktemp)"
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$jar" | tail -n 1; }

curl -fsS "$base/" | grep -q '<div id="app">' || fail "首頁不是前端的 index.html"
pass "首頁"

curl -fsS -c "$jar" -b "$jar" "$base/api/auth/providers" >/dev/null
user="verify$(date +%s)"
status=$(curl -sS -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(xsrf)" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$user\",\"password\":\"verify-password\"}" "$base/api/auth/register")
[ "$status" = 201 ] || fail "註冊回 $status"
pass "註冊（寫進 PostgreSQL）"

status=$(curl -sS -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(xsrf)" \
  --data-urlencode "username=$user" --data-urlencode "password=verify-password" "$base/api/auth/login")
[ "$status" = 204 ] || fail "登入回 $status"
pass "登入"

# 登入後 Spring Security 會換一個新的 CSRF token，先打一個 GET 拿新的 cookie
curl -fsS -c "$jar" -b "$jar" "$base/api/auth/me" >/dev/null
reply="$(curl -fsS -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
  -d '{"message":"明天下午三點開會"}' "$base/api/calendar/assistant")"
echo "$reply"
grep -q 'mock LLM' <<<"$reply" || fail "助理的回覆不是從 llm-chat 來的"
pass "助理：web → app → llm-chat，回覆存進對話記憶"

step "完成：乾淨的 $(uname -s) 照 README 從 0 起起來了"
