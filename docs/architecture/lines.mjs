#!/usr/bin/env node
// 架構圖（index.html）的 F、L 表記錨點，不記行號：檔案中間多一行、少一行，表都不用改，
// 好幾個改 infra 的 PR 同時開著也不會在這裡衝突（#275）。這支把錨點換成行號：
//
//   node docs/architecture/lines.mjs --check        每個錨點都找得到、只對到一處；有問題全部列出來並失敗（ci/checks.sh）
//   node docs/architecture/lines.mjs --out <資料夾>  寫出換成行號的頁面（pages.yml 發佈、本機預覽都用這個）
//
// 錨點是一個字串：
//   at          含有 at 的那一行，整份檔案只能有一行。at 開頭是 ^ 時改比對行首（含縮排），例如 "^  postgres:"
//   in › at     只在 in 那一行的區塊裡找 at（in 本身在整份檔案只能有一行），at 在檔案別處也出現時用
//   at – to     範圍：從 at 那一行，到含有 to 的那一行的區塊結尾；to 在 at 之後也只能有一行
//   in › at – to  同上，to 也只在 in 的區塊裡找
//   全檔        整份檔案（只有 F 表能用）
// 比對前，行和錨點裡連續的空白都當成一個空格（縮排除外）：terraform fmt 重新對齊 = 不會讓錨點失效。
// 區塊：從那一行往下，縮排比它深的行都算；接著同一縮排的收尾行（}、]、)、fi、done、esac）也算。
//   縮排不夠深的註解（# 或 // 開頭）跳過、不算結束：區塊中間常有頂格寫的註解。
// F 表的範圍預設就是 at 那一行的區塊；L 表只取 at 那一行。
// 要求只對到一行，是為了讓「有人多加了一行長得像的」變成 CI 紅燈，不會默默指到別行。
//
// 只用 Node 內建模組：CI 容器（ci/Dockerfile）和 GitHub 的 runner 都有 Node，不必另外裝套件。

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const SELF = fileURLToPath(import.meta.url);
const HERE = path.dirname(SELF);
const ROOT = path.resolve(HERE, "../..");
const PAGE = path.join(HERE, "index.html");
// 資料區塊的頭尾：--out 把中間整段換成算好行號的 F、L，所以這段只能放 F、L 和 L 用的簡寫
const BEGIN = "// ── 行號錨點：開始", END = "// ── 行號錨點：結束";
const IN = " › ", TO = " – ", WHOLE = "全檔";
const CLOSER = /^([}\])]|fi\b|done\b|esac\b)/, COMMENT = /^(#|\/\/)/;

// 拆行的方式要跟頁面的 getFile 一樣，算出的行號在頁內的原始碼視窗才標得對
const readLines = text => {
  const ls = text.replace(/\r\n/g, "\n").split("\n");
  if (ls[ls.length - 1] === "") ls.pop();
  return ls;
};
const indent = s => s.length - s.trimStart().length;
const squeeze = s => s.slice(0, indent(s)) + s.trimStart().replace(/\s+/g, " ");
const matches = (line, text) => {
  const l = squeeze(line);
  return text.startsWith("^") ? l.startsWith(squeeze(text.slice(1))) : l.includes(squeeze(text).trimStart());
};

// n 那一行的區塊結尾（行號從 1 起算）
function blockEnd(ls, n) {
  const base = indent(ls[n - 1]);
  let end = n;
  for (let i = n + 1; i <= ls.length; i++) {
    const t = ls[i - 1];
    if (t.trim() === "") continue;
    if (indent(t) > base) { end = i; continue; }
    if (COMMENT.test(t.trim())) continue;
    if (indent(t) === base && CLOSER.test(t.trim())) end = i;
    break;
  }
  return end;
}

const where = ns => ns.slice(0, 5).join("、") + (ns.length > 5 ? "…" : "");
function findOne(ls, text, from, to, what) {
  const hits = [];
  for (let i = from; i <= to; i++) if (matches(ls[i - 1], text)) hits.push(i);
  if (hits.length === 1) return hits[0];
  const range = from === 1 && to === ls.length ? "" : `第 ${from}–${to} 行裡`;
  throw new Error(hits.length ? `${what} ${JSON.stringify(text)} ${range}對到 ${hits.length} 行（${where(hits)}），要寫得更明確${what === "at" ? "或加 in ›" : ""}` : `${what} ${JSON.stringify(text)} ${range}找不到`);
}

// 行號的寫法（"5–14"、"53"）要跟頁面的 range() 認得的一樣，不然頁面會把行號當成錨點、不帶 #L
const NUMBER = /^\d+(–\d+)?$/;
const label = r => r ? (r[0] === r[1] ? `${r[0]}` : `${r[0]}–${r[1]}`) : WHOLE;

// 錨點 → [起, 迄]；全檔 → null
function resolve(ls, spec) {
  if (spec === WHOLE) return null;
  if (typeof spec !== "string" || !spec) throw new Error(`錨點要是字串，拿到 ${JSON.stringify(spec)}`);
  if (NUMBER.test(spec)) throw new Error(`${JSON.stringify(spec)} 是寫死的行號，改寫成錨點`);
  const scoped = spec.split(IN);
  if (scoped.length > 2) throw new Error(`${JSON.stringify(spec)} 有兩個以上的「${IN.trim()}」`);
  const [scope, rest] = scoped.length === 2 ? scoped : [null, scoped[0]];
  const ranged = rest.split(TO);
  if (ranged.length > 2) throw new Error(`${JSON.stringify(spec)} 有兩個以上的「${TO.trim()}」`);
  const [at, until] = ranged;
  let from = 1, to = ls.length;
  if (scope !== null) { from = findOne(ls, scope, 1, ls.length, "in"); to = blockEnd(ls, from); }
  const start = findOne(ls, at, from, to, "at");
  if (until === undefined) return [start, blockEnd(ls, start)];
  if (start === to) throw new Error(`to ${JSON.stringify(until)}：at 已經是最後一行`);
  return [start, blockEnd(ls, findOne(ls, until, start + 1, to, "to"))];
}

function extract(html) {
  const a = html.indexOf(BEGIN), b = html.indexOf(END);
  if (a < 0 || b < a) throw new Error(`index.html 找不到「${BEGIN}」到「${END}」這段`);
  const code = html.slice(html.indexOf("\n", a) + 1, b);
  return { a, b: html.indexOf("\n", b) + 1, ...vm.runInNewContext(`${code};({F,L})`) };
}

// 把 F、L 的錨點都換成行號。錯誤全部收集起來，一次列出
function resolveAll(F, L) {
  const errors = [], cache = {};
  let count = 0;
  const tryResolve = (f, spec, where) => {
    count++;
    try {
      return resolve(cache[f] ??= readLines(fs.readFileSync(path.join(ROOT, f), "utf8")), spec);
    } catch (e) { errors.push(`${where}：${e.code === "ENOENT" ? "檔案不存在" : e.message}`); return undefined; }
  };
  const F2 = {};
  for (const [f, rows] of Object.entries(F)) {
    F2[f] = rows.map(([spec, desc, key]) => [label(tryResolve(f, spec, `F["${f}"]「${desc}」`)), desc, key]);
  }
  const loc = (v, where) => {
    const i = v.indexOf(":"); // 路徑裡沒有冒號，第一個冒號後面整段都是錨點
    if (i < 0) { errors.push(`${where}：${JSON.stringify(v)} 要寫成「檔案:錨點」`); return v; }
    const f = v.slice(0, i), r = tryResolve(f, v.slice(i + 1), where);
    if (r === null) errors.push(`${where}：L 表要指到一行，不能用${WHOLE}`);
    return r ? `${f}:${r[0]}` : v;
  };
  const L2 = {};
  for (const [k, ps] of Object.entries(L)) {
    L2[k] = {};
    for (const [p, v] of Object.entries(ps)) {
      L2[k][p] = typeof v === "string" ? loc(v, `L.${k}["${p}"]`)
        : Object.fromEntries(Object.entries(v).map(([m, vv]) => [m, loc(vv, `L.${k}["${p}"].${m}`)]));
    }
  }
  return { F: F2, L: L2, errors, count };
}

// 發佈版的原始碼連結、頁內原始碼視窗固定在算行號的那個 commit：
// 之後某次發佈失敗、線上留著舊版時，舊的行號也還是對著它自己那一版的原始碼
function pin(html, sha) {
  for (const k of ["REPO", "RAW"]) {
    const re = new RegExp(`(const ${k}="[^"]*/)master/"`);
    if (!re.test(html)) throw new Error(`index.html 找不到 const ${k}="…/master/"，原始碼連結沒辦法固定在 ${sha}`);
    html = html.replace(re, `$1${sha}/"`);
  }
  return html;
}

function main(args) {
  const html = fs.readFileSync(PAGE, "utf8");
  const { a, b, F, L } = extract(html);
  const r = resolveAll(F, L);
  if (r.errors.length) {
    console.error(`架構圖有 ${r.errors.length} 個錨點對不上檔案（docs/architecture/index.html；寫法見 docs/architecture/lines.mjs 開頭）：`);
    r.errors.forEach(e => console.error(`  ${e}`));
    return 1;
  }
  if (args[0] === "--check" && args.length === 1) { console.log(`架構圖的 ${r.count} 個錨點都對得上`); return 0; }
  if (args[0] === "--out" && args.length === 2) {
    const out = path.resolve(args[1]);
    if (out === HERE || out.startsWith(HERE + path.sep)) { console.error("--out 要指到 docs/architecture/ 外面的資料夾：寫在裡面會蓋掉原始檔，或把自己複製進自己"); return 2; }
    fs.mkdirSync(out, { recursive: true });
    // 資料夾裡其他檔案原樣帶過去（這支腳本除外，它不是網頁的一部分）
    fs.cpSync(HERE, out, { recursive: true, filter: s => path.resolve(s) !== SELF });
    let page = html.slice(0, a) + `const F=${JSON.stringify(r.F)};\nconst L=${JSON.stringify(r.L)};\n` + html.slice(b);
    // GitHub Actions 才有 GITHUB_SHA；本機預覽照樣連 master
    if (process.env.GITHUB_SHA) page = pin(page, process.env.GITHUB_SHA);
    fs.writeFileSync(path.join(out, "index.html"), page);
    console.log(`架構圖的 ${r.count} 個錨點換成行號，寫到 ${path.join(out, "index.html")}`);
    return 0;
  }
  console.error("用法：node docs/architecture/lines.mjs --check | --out <資料夾>");
  return 2;
}

process.exitCode = main(process.argv.slice(2));
