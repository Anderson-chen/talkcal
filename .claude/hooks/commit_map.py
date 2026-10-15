"""從 git 產生「commit 脈絡圖」HTML：每個 commit 的原因（commit 訊息），每個檔案的用途（那個版本的檔案開頭註解）＋連結。

用法：python .claude/hooks/commit_map.py [分支，預設目前分支] [基準，預設 origin/master]
輸出到 build/commit-map/<分支>.html（build/ 被 gitignore）。內容全部來自 git，不需要手寫，所以每次重跑都是最新的。
--hook：給 .claude/settings.json 的 PostToolUse hook 用（開 PR 之後自動跑），輸出改成 hook 的 JSON，把檔案路徑回報給 Claude。
"""
import html, json, re, subprocess, sys
from pathlib import Path

hook = '--hook' in sys.argv
args = [a for a in sys.argv[1:] if a != '--hook']
if hook:
    sys.stdin.read()  # hook 的輸入（這次的 Bash 指令）用不到；要不要跑已經由 settings.json 的 if 決定

def git(*args):
    return subprocess.run(['git', '-C', str(repo), *args], capture_output=True, text=True, encoding='utf-8', errors='replace').stdout

repo = Path('.')
repo = Path(git('rev-parse', '--show-toplevel').strip())
branch = args[0] if args else git('branch', '--show-current').strip()
base = args[1] if len(args) > 1 else 'origin/master'
out = repo / 'build' / 'commit-map' / (branch.replace('/', '-') + '.html')
out.parent.mkdir(parents=True, exist_ok=True)

url = git('remote', 'get-url', 'origin').strip().removesuffix('.git').replace('git@github.com:', 'https://github.com/')
COMMENT = re.compile(r'^\s*(//+|#+|--|/\*\*?|\*|<!--)\s?(.*?)(\*/|-->)?\s*$')

def purpose(sha, path):
    """檔案在那個 commit 版本的開頭註解（前兩句），沒有就空白。"""
    text = git('show', f'{sha}:{path}')
    lines = []
    for raw in text.splitlines()[:40]:
        m = COMMENT.match(raw)
        if m and m.group(2).strip() and not m.group(2).strip().startswith(('!', 'eslint', '@')):
            lines.append(m.group(2).strip())
            if len(lines) == 2:
                break
        elif lines:
            break
    return ' '.join(lines)

MAX_LINES = 400

TYPE_PREFIX = re.compile(r'^\[(\w+)\]')
TYPES = {'feat': '功能', 'fix': '修問題', 'test': '測試', 'docs': '文件', 'refactor': '重構', 'ci': 'CI', 'chore': '雜項'}

# Java 的固定前綴不顯示：src/main/java/io/github/andersonchen/talkcal/ 這段每個檔案都一樣，只會把檔名擠到後面
JAVA = re.compile(r'src/(main|test|integrationTest|eval)/(java|resources)/(io/github/andersonchen/talkcal/)?')
SOURCE_SET = {'main': '', 'test': '（單元測試）', 'integrationTest': '（整合測試）', 'eval': '（eval）'}

def display_path(path):
    """檔名在前（粗體），資料夾縮短放後面（淡色）；完整路徑放在 title 裡，滑鼠停上去看得到。"""
    folder, _, name = path.rpartition('/')
    m = JAVA.search(folder + '/')
    if m:
        module = folder[:m.start()].rstrip('/')
        rest = (folder + '/')[m.end():].rstrip('/')
        folder = module + SOURCE_SET[m.group(1)] + (f' › {rest}' if rest else '')
    return f'<b>{html.escape(name)}</b> <span class="dir">{html.escape(folder)}</span>'

HUNK = re.compile(r'^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)')

def _table(rows, extra):
    note = f'<tr><td colspan="4" class="more">…還有 {extra} 行沒顯示，完整內容點 ↗ 到 GitHub 看</td></tr>' if extra > 0 else ''
    return f'<div class="code"><table class="d">{"".join(rows)}{note}</table></div>'

def block(text, diff=False):
    """程式碼表格：左邊行號、整列上色（GitHub 那種樣子）。diff 不顯示 git 的檔頭，每段改動前一條「第幾行」分隔條。"""
    rows, count = [], 0
    if not diff:
        lines = text.splitlines()
        for n, line in enumerate(lines[:MAX_LINES], 1):
            rows.append(f'<tr><td class="ln">{n}</td><td class="ln"></td><td class="sg"></td><td class="c">{html.escape(line)}</td></tr>')
        return _table(rows, len(lines) - MAX_LINES)
    old = new = 0
    started = False
    for line in text.splitlines():
        m = HUNK.match(line)
        if m:
            started = True
            old, new = int(m.group(1)), int(m.group(2))
            where = html.escape(m.group(3).strip())
            rows.append(f'<tr class="hk"><td colspan="3"></td><td>第 {new} 行附近{" · " + where if where else ""}</td></tr>')
            continue
        if not started or line.startswith('\\'):
            continue
        count += 1
        if count > MAX_LINES:
            continue
        sign, code = line[:1], html.escape(line[1:])
        if sign == '+':
            rows.append(f'<tr class="a"><td class="ln"></td><td class="ln">{new}</td><td class="sg">+</td><td class="c">{code}</td></tr>')
            new += 1
        elif sign == '-':
            rows.append(f'<tr class="r"><td class="ln">{old}</td><td class="ln"></td><td class="sg">−</td><td class="c">{code}</td></tr>')
            old += 1
        else:
            rows.append(f'<tr><td class="ln">{old}</td><td class="ln">{new}</td><td class="sg"></td><td class="c">{code}</td></tr>')
            old += 1
            new += 1
    return _table(rows, count - MAX_LINES)

AREAS = ['後端', '後端測試', '前端', '前端測試', '基礎設施', '壓測', '文件']

def area(path):
    """檔案屬於哪一塊：總覽表的欄位。"""
    if path.startswith('docs/') or path.endswith('.md'): return '文件'
    if path.startswith('backend/src/main/'): return '後端'
    if path.startswith('backend/src/'): return '後端測試'
    if path.startswith('web/e2e/') or path.endswith(('.test.ts', '.spec.ts')): return '前端測試'
    if path.startswith('web/') and not path.endswith(('nginx.conf', 'Dockerfile')): return '前端'
    if path.startswith('perf/'): return '壓測'
    return '基礎設施'

ICON = {'A': ('新增', '#1D9E75'), 'M': ('修改', '#378ADD'), 'D': ('刪除', '#D85A30'), 'R': ('改名', '#BA7517')}
commits = git('log', '--reverse', '--no-merges', '--format=%H%x1f%h%x1f%ad%x1f%s%x1f%b%x1e', '--date=format:%m-%d %H:%M', f'{base}..{branch}')
cards = []
tabs = []
overview = []
for entry in filter(str.strip, commits.split('\x1e')):
    sha, short, date, subject, body = entry.strip('\n').split('\x1f')
    stats = {p: (a, d) for a, d, p in (l.split('\t') for l in git('show', '--numstat', '--format=', sha).splitlines() if l.count('\t') == 2)}
    rows = []
    for line in git('show', '--name-status', '--format=', sha).splitlines():
        status, *paths = line.split('\t')
        path = paths[-1]
        label, color = ICON.get(status[0], (status, '#888'))
        add, rem = stats.get(path, ('', ''))
        link = f'{url}/blob/{sha}/{path}' if status[0] != 'D' else f'{url}/commit/{sha}'
        # 點檔名在頁面裡展開：先看這次的 diff，再一層是那個版本的完整檔案；↗ 才連到 GitHub
        diff = git('show', '--format=', '-U3', sha, '--', path)
        full = '' if status[0] == 'D' else f'<details><summary>完整檔案（這個 commit 的版本）</summary>{block(git("show", f"{sha}:{path}"))}</details>'
        rows.append((status[0], path, f'<tr><td class="n"><span class="tag" style="background:{color}">{label}</span> '
                    f'<span class="plus">+{add}</span> <span class="minus">−{rem}</span> <a href="{link}" title="在 GitHub 打開">↗</a></td>'
                    f'<td><details><summary title="{html.escape(path)}">{display_path(path)}</summary>{block(diff, diff=True)}{full}</details></td>'
                    f'<td>{html.escape(purpose(sha, path)) if status[0] != "D" else ""}</td></tr>'))
    problem = any(k in subject + body for k in ('修', '問題', '失敗', 'bug', '原本', '沒進', '漏'))
    # 檔案分組：新增、修改、改名、刪除各一組，不混在一起
    grouped = []
    for code in 'AMRD':
        group = [r for st, _, r in rows if st == code]
        if group:
            grouped.append(f'<tr class="group"><td colspan="3">{ICON[code][0]}（{len(group)}）</td></tr>' + ''.join(group))
    counts = {a: 0 for a in AREAS}
    for _, path, _ in rows:
        counts[area(path)] += 1
    touched = {a for a, n in counts.items() if n}
    # 類型以 commit 訊息開頭的 [類型] 為準（CLAUDE.md、#112）；沒有前綴的舊 commit 才從關鍵字和改到的檔案猜
    prefix = TYPE_PREFIX.match(subject)
    if prefix and prefix.group(1) in TYPES:
        kind = TYPES[prefix.group(1)]
    else:
        problem = problem and not touched <= {'文件'}
        kind = ('文件' if touched <= {'文件'} else '修問題' if problem else '測試' if touched <= {'後端測試', '前端測試'} else '功能')
    problem = kind == '修問題'
    i = len(cards) + 1
    overview.append((i, short, kind, subject, counts))
    label = subject if len(subject) <= 14 else subject[:14] + '…'
    tabs.append(f'<button class="tab{" problem" if problem else ""}" data-i="{i}" title="{html.escape(subject)}">'
                f'<code>{short}</code> {html.escape(label)}</button>')
    cards.append(f'<section data-i="{i}" class="{"problem" if problem else ""}"><h2><a href="{url}/commit/{sha}">{short}</a> '
                 f'<small>{date}</small> {html.escape(subject)}</h2>'
                 + (f'<p class="body">{html.escape(body.strip())}</p>' if body.strip() else '')
                 + f'<table><tr><th>變動</th><th>檔案（點開看 diff 和完整內容）</th><th>檔案的用途（開頭註解）</th></tr>{"".join(grouped)}</table></section>')

KIND_COLOR = {'功能': '#378ADD', '測試': '#1D9E75', '修問題': '#D85A30', '文件': '#888780', '重構': '#7F77DD', 'CI': '#BA7517', '雜項': '#5F5E5A'}
head = ''.join(f'<th>{a}</th>' for a in AREAS)
lines = ''.join(
    f'<tr class="go" data-go="{i}"><td><code>{sh}</code></td><td><span class="tag" style="background:{KIND_COLOR[k]}">{k}</span></td>'
    f'<td>{html.escape(sub)}</td>' + ''.join(f'<td class="cell">{c[a] or ""}</td>' for a in AREAS) + '</tr>'
    for i, sh, k, sub, c in overview)
fixes = sum(1 for o in overview if o[2] == '修問題')
tabs.insert(0, '<button class="tab" data-i="0"><b>總覽</b></button>')
cards.insert(0, f'<section data-i="0"><h2>為什麼分成 {len(overview)} 個 commit</h2>'
             f'<p class="body">一個 commit ＝ 一個能單獨驗證的步驟（各自跑過 CI）。每一列是一個 commit，格子裡是它在那一塊改了幾個檔：'
             f'一眼看得出每一步負責哪一層。其中 {fixes} 個是做到一半發現問題、另外修掉才多出來的（橘色）。點一列看細節。</p>'
             f'<table class="ov"><tr><th></th><th>類型</th><th>做了什麼</th>{head}</tr>{lines}</table></section>')

out.write_text(f'''<!doctype html><meta charset="utf-8"><title>{branch} commit 脈絡</title>
<style>
:root{{--bg:#16181f;--card:#1d2029;--fg:#e6e8ee;--muted:#9aa1b0;--line:#2c303b;--code:#12141a;color-scheme:dark}}
body{{background:var(--bg);color:var(--fg);font:14px/1.6 'Noto Sans TC',sans-serif;max-width:1100px;margin:auto;padding:16px}}
a{{color:#7C9BFF}} h1{{font-size:20px}} h2{{font-size:15px;margin:0 0 6px}} small{{color:var(--muted);font-weight:400}}
section{{background:var(--card);border-left:3px solid var(--line);border-radius:0 8px 8px 0;padding:12px 14px;margin:0 0 16px}} section.problem{{border-color:#D85A30}}
.body{{white-space:pre-wrap;color:var(--muted);margin:0 0 6px}} table{{border-collapse:collapse;width:100%}}
td,th{{border-top:1px solid var(--line);padding:4px 6px;text-align:left;vertical-align:top;font-size:13px}} th{{color:var(--muted);font-weight:400}}
summary{{cursor:pointer}} .code{{max-height:520px;overflow:auto;border:1px solid var(--line);border-radius:6px;margin:8px 0;background:var(--code)}}
.d{{border-collapse:collapse;width:100%;font:12px/1.5 Consolas,'Cascadia Mono',monospace}} .d td{{border:0;padding:0 8px;vertical-align:top;font-size:12px}}
.d .ln{{color:#5d6472;text-align:right;user-select:none;width:1%;white-space:nowrap}} .d .sg{{width:1%;user-select:none;color:var(--muted)}}
.d .c{{white-space:pre;color:#cfd3dc}} .d .a{{background:rgba(46,160,67,.18)}} .d .a .sg{{color:#5DCAA5}} .d .r{{background:rgba(248,81,73,.16)}} .d .r .sg{{color:#F0997B}}
.d .hk td{{background:rgba(124,155,255,.12);color:#9fb3ff;padding:2px 8px}} .d .more{{color:var(--muted);padding:6px 8px}}
.dir{{color:var(--muted);font-size:12px;margin-left:6px}} .add{{color:#5DCAA5}} .del{{color:#F0997B}} .hunk{{color:#85B7EB}} .plus{{color:#5DCAA5}} .minus{{color:#F0997B}}
nav{{position:sticky;top:0;z-index:1;background:var(--bg);display:flex;flex-wrap:wrap;gap:4px;padding:8px 0;border-bottom:1px solid var(--line);margin-bottom:12px}}
.tab{{background:var(--card);color:var(--muted);border:1px solid var(--line);border-radius:6px;padding:4px 10px;font:13px 'Noto Sans TC',sans-serif;cursor:pointer}}
.tab.on{{color:var(--fg);border-color:#7C9BFF;background:#253158}} .tab.problem{{border-left:3px solid #D85A30}} .tab code{{color:#7C9BFF}}
.group td{{color:var(--muted);font-size:12px;padding-top:10px;border-top:1px solid var(--line)}}
.ov .cell{{text-align:center;color:#7C9BFF;font-weight:500}} .ov th{{white-space:nowrap}} .go{{cursor:pointer}} .go:hover{{background:#253158}}
.tag{{color:#fff;border-radius:4px;padding:0 6px;font-size:12px;white-space:nowrap}} .n{{color:var(--muted);white-space:nowrap}}
</style><h1>{html.escape(branch)}：{len(cards)} 個 commit（相對 {html.escape(base)}）</h1>
<p><small>第一頁是總覽，後面每個分頁是一個 commit（←→ 切換）；橘色＝修問題（commit 訊息的 [fix]）；檔案用途取自該版本的檔案開頭註解。重新產生：python .claude/hooks/commit_map.py {html.escape(branch)}</small></p>
<nav>{"".join(tabs)}</nav>
{"".join(cards)}
<script>
const tabs=[...document.querySelectorAll('.tab')], secs=[...document.querySelectorAll('section')];
function show(i){{tabs.forEach(t=>t.classList.toggle('on',t.dataset.i==i));secs.forEach(s=>s.hidden=s.dataset.i!=i);location.hash=i}}
tabs.forEach(t=>t.onclick=()=>show(t.dataset.i));
document.querySelectorAll('.go').forEach(r=>r.onclick=()=>show(r.dataset.go));
document.onkeydown=e=>{{const c=+location.hash.slice(1)||0;if(e.key=='ArrowRight')show(Math.min(c+1,secs.length-1));if(e.key=='ArrowLeft')show(Math.max(c-1,0))}};
show(+location.hash.slice(1)||0);
</script>''', encoding='utf-8')
if hook:
    print(json.dumps({'hookSpecificOutput': {'hookEventName': 'PostToolUse',
                      'additionalContext': f'已產生這個分支的 commit 脈絡圖：{out.resolve()}，用 SendUserFile 傳給使用者。'}}))
else:
    print(out.resolve())
