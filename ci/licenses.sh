#!/usr/bin/env bash
# 前端套件的授權檢查：會打包進前端、送到使用者瀏覽器的套件，不能是 GPL、AGPL 這類 copyleft，也不能查不出授權。
# 後端的在 Gradle 的 checkLicense（backend/build.gradle），規則和理由整理在 docs/licenses.md。
#
#   ci/licenses.sh        由 ci/checks.sh 在 CI 容器裡呼叫（容器裡有 node、Trivy）
#
# 為什麼先另外裝一份「只有 dependencies」的 node_modules 再掃：
#   - devDependencies（vite、vitest、playwright…）只在 build、測試時用，不會進到 dist/，不算散布出去，不必管
#   - 不讀 lockfile 而是讀裝好的 package.json：pnpm-lock.yaml 沒有記授權 ——
#     直接掃它時 Trivy 找得到 24 個套件、卻一個授權都沒有，檢查會靜靜地通過（2026-10-19 實測）。
#     裝好的 node_modules 裡每個套件都有自己的 package.json，讀得到授權
#   - 套件從 pnpm store 拿（run.sh 掛的 volume），前面 checks.sh 的 pnpm install 已經下載過，這裡不會再連網
#
# 用 Trivy（ci/vulns.sh 已經在用、版本釘死），不另外引入 license-checker 這類套件：
# 它照 SPDX 把授權分成幾類並給嚴重度，這裡擋 CRITICAL（forbidden：AGPL、SSPL、CC-BY-NC…）、
# HIGH（restricted：GPL、LGPL…）和 UNKNOWN（認不出來的授權字串）。MPL、EPL 這類只限檔案本身的 copyleft 是 MEDIUM，放行。
#
# 不連網：授權分類內建在 Trivy 裡，不必下載漏洞資料庫。

set -euo pipefail
cd "$(dirname "$0")/.."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# pnpm-workspace.yaml 一起帶：pnpm 11 的設定（例如只裝滿兩週的版本）寫在那裡，少了它裝出來的跟 CI 的不一樣
cp web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml "$work/"
(cd "$work" && pnpm install --prod --frozen-lockfile --ignore-scripts >/dev/null)

trivy rootfs --quiet --skip-db-update --scanners license --list-all-pkgs --format json \
    --output "$work/report.json" "$work"

# 判斷寫在這裡，不交給 Trivy 的 --exit-code：要多擋兩種 Trivy 不會報的情況 ——
#   一個套件都沒掃到（例如 node_modules 沒裝起來），和套件的 package.json 沒寫授權
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
