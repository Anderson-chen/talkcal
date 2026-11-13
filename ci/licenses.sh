#!/usr/bin/env bash
# 前端套件的授權檢查：會打包送到使用者瀏覽器的套件，不能是 GPL、AGPL 這類 copyleft，也不能查不出授權。
# 規則和例外怎麼處理見 docs/licenses.md；後端的在 Gradle 的 checkLicense。
#
#   ci/licenses.sh        要有 node 和 Trivy，CI 容器（ci/Dockerfile）裡有
#
# 先另外裝一份只有 dependencies 的 node_modules 再掃：
#   - devDependencies 只在 build、測試時用，不會進 dist/，不算散布出去
#   - pnpm-lock.yaml 沒有記授權，直接掃它的話 Trivy 找得到套件、卻一個授權都沒有，檢查會默默通過。
#     裝好的每個套件都有自己的 package.json，讀得到授權
#   - 套件從 pnpm store 拿，checks.sh 前面的 pnpm install 已經下載過，不會再連網
#
# Trivy 照 SPDX 分類授權並給嚴重度：擋 CRITICAL（AGPL、SSPL、CC-BY-NC…）、HIGH（GPL、LGPL…）和 UNKNOWN（認不出來的）。
# MPL、EPL 這類只限檔案本身的 copyleft 是 MEDIUM，放行。授權分類內建在 Trivy 裡，不必下載漏洞資料庫。

set -euo pipefail
cd "$(dirname "$0")/.."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# pnpm-workspace.yaml 也要帶：pnpm 的設定（例如只裝發布滿兩週的版本）寫在那裡，少了它裝出來的版本會不一樣
cp web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml "$work/"
# 輸出不丟掉：裝不起來時 pnpm 把原因（ERR_PNPM_…）印在 stdout
(cd "$work" && pnpm install --prod --frozen-lockfile --ignore-scripts)

trivy rootfs --quiet --skip-db-update --scanners license --list-all-pkgs --format json \
    --output "$work/report.json" "$work"

# 不用 Trivy 的 --exit-code，自己判斷：還要擋兩種 Trivy 不會報的情況，
# 一個套件都沒掃到（例如 node_modules 沒裝起來），和套件的 package.json 沒寫授權
node - "$work/report.json" <<'EOF'
const report = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
const results = report.Results ?? [];
const packages = results.flatMap(r => r.Packages ?? []);
const findings = results.flatMap(r => r.Licenses ?? []);
const blocked = new Set(['CRITICAL', 'HIGH', 'UNKNOWN']);

const problems = [];
if (packages.length === 0) problems.push('一個套件都沒掃到：node_modules 沒裝起來，或 Trivy 讀不到');
const counts = {};
for (const p of packages) {
    if (!p.Licenses?.length) problems.push(`${p.Name}@${p.Version}：package.json 沒有寫授權`);
    for (const l of p.Licenses ?? []) counts[l] = (counts[l] ?? 0) + 1;
}
for (const f of findings) {
    if (blocked.has(f.Severity)) problems.push(`${f.PkgName}：${f.Name}（${f.Category}，${f.Severity}）`);
}

console.log(`前端會打包的套件 ${packages.length} 個：` +
    Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([l, n]) => `${l} ×${n}`).join('、'));

if (problems.length) {
    console.log('不允許的授權：');
    for (const p of problems) console.log(`  - ${p}`);
    console.log('規則和例外的處理方式見 docs/licenses.md');
    process.exit(1);
}
EOF
