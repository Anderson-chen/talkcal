#!/usr/bin/env python3
"""從 GitHub 撈交付紀錄，算 DORA 的五個指標，印成 markdown 報告。

定義和理由見 cd/dora/README.md。只讀 GitHub（gh api 的 GET），不寫任何東西。

    python cd/dora/dora.py                          # 全部歷史，環境 local
    python cd/dora/dora.py --days 30                # 最近 30 天
    python cd/dora/dora.py --save-snapshot s.json   # 順便把撈到的原始資料存下來
    python cd/dora/dora.py --snapshot s.json        # 不連 GitHub，用存下來的資料算（測試也走這條）

分三段：fetch（連 GitHub）→ compute（純函式，只吃 snapshot）→ render（印 markdown）。
compute 不碰網路，所以反例測試可以餵造出來的 snapshot（test_dora.py）。
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

TPE = timezone(timedelta(hours=8))
# hotfix.md 第 0 步：線上出事開的 issue 是 label bug、標題「線上：<症狀>」
INCIDENT_LABEL = "bug"
INCIDENT_PREFIX = "線上："
TERMINAL = {"success", "failure", "error"}

PR_QUERY = """
query($owner: String!, $name: String!, $endCursor: String) {
  repository(owner: $owner, name: $name) {
    pullRequests(states: MERGED, baseRefName: "master", first: 100, after: $endCursor) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number title mergedAt mergeCommit { oid }
        commits(first: 1) { nodes { commit { authoredDate } } }
        closingIssuesReferences(first: 10) { nodes { number } }
      }
    }
  }
}"""


# ───────────────────────── fetch ─────────────────────────

def gh_json_lines(args: list[str]) -> list:
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def gh_json(args: list[str]):
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout
    return json.loads(out)


def fetch(repo: str, env: str) -> dict:
    """撈算指標要的全部原始資料。全部是 GET。"""
    deployments = gh_json_lines(["api", "--paginate", f"repos/{repo}/deployments?environment={env}&per_page=100",
                                 "--jq", ".[] | {id, sha, environment, created_at, description}"])
    for d in deployments:
        d["statuses"] = gh_json_lines(["api", "--paginate", f"repos/{repo}/deployments/{d['id']}/statuses?per_page=100",
                                       "--jq", ".[] | {state, created_at}"])
    # master 上的 commit，新的在前：拿來判斷「部署 X 有沒有包含 commit Y」（master 是 squash merge，一條直線）
    master = gh_json_lines(["api", "--paginate", f"repos/{repo}/commits?sha=master&per_page=100", "--jq", ".[].sha | tojson"])
    # 不用 gh pr list --json commits：它連 commit 的 authors 一起要，PR 一多就超過 GraphQL 50 萬節點的上限。
    # 前置時間只要第一個 commit，自己寫查詢、commits(first: 1)
    owner, name = repo.split("/")
    prs = gh_json_lines(["api", "graphql", "--paginate", "-F", f"owner={owner}", "-F", f"name={name}",
                         "-f", f"query={PR_QUERY}", "--jq", ".data.repository.pullRequests.nodes[] | tojson"])
    issues = gh_json(["issue", "list", "--repo", repo, "--state", "all", "--limit", "2000",
                      "--json", "number,title,createdAt,closedAt,labels"])
    return {
        "repo": repo,
        "environment": env,
        "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "master_commits": master,
        "deployments": deployments,
        "prs": [{
            "number": p["number"],
            "title": p["title"],
            "merged_at": p["mergedAt"],
            "merge_sha": (p.get("mergeCommit") or {}).get("oid"),
            # authoredDate：rebase 會改 committedDate，authoredDate 才是「第一次寫下這個改動」
            "first_commit_at": min((c["commit"]["authoredDate"] for c in p["commits"]["nodes"]), default=p["mergedAt"]),
            "closing_issues": [i["number"] for i in p["closingIssuesReferences"]["nodes"]],
        } for p in prs],
        "issues": [{
            "number": i["number"],
            "title": i["title"],
            "created_at": i["createdAt"],
            "closed_at": i["closedAt"],
            "labels": [l["name"] for l in i["labels"]],
        } for i in issues],
    }


# ───────────────────────── compute ─────────────────────────

def ts(s: str | None) -> datetime | None:
    return datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None


@dataclass
class Deploy:
    id: int
    sha: str
    created: datetime
    finished: datetime | None   # 第一個終態（success/failure/error）的時間；沒有就是 None
    state: str                  # success / failure / error / unfinished
    description: str
    on_master: bool
    master_idx: int | None      # master_commits 裡的位置，0 是最新；不在 master（hotfix 分支）就是 None


@dataclass
class Failure:
    deploy: Deploy
    reasons: list[str] = field(default_factory=list)
    start: datetime | None = None       # 開始壞的時間
    recovered: datetime | None = None   # 恢復的時間（下一個把問題解掉的成功部署完成）
    recovered_by: str = ""

    @property
    def recovery(self) -> timedelta | None:
        return self.recovered - self.start if self.recovered and self.start else None


@dataclass
class Result:
    window_start: datetime | None
    window_end: datetime
    deploys: list[Deploy]
    attempts: list[Deploy]
    successes: list[Deploy]
    unfinished: list[Deploy]
    lead: list[dict]
    undeployed: list[dict]
    before_cd: list[dict]
    failures: list[Failure]
    rework: list[tuple[Deploy, str]]
    incidents: list[dict]
    first_deploy: datetime | None   # 這個環境全部歷史的第一筆部署（不受區間影響）


def load_deploys(snap: dict) -> list[Deploy]:
    idx = {sha: i for i, sha in enumerate(snap["master_commits"])}
    out = []
    for d in snap["deployments"]:
        if d.get("environment") not in (None, snap["environment"]):
            continue
        # statuses API 是新的在前；取時間最早的那個終態
        terminal = sorted((s for s in d["statuses"] if s["state"] in TERMINAL), key=lambda s: s["created_at"])
        first = terminal[0] if terminal else None
        out.append(Deploy(
            id=d["id"], sha=d["sha"], created=ts(d["created_at"]),
            finished=ts(first["created_at"]) if first else None,
            state=first["state"] if first else "unfinished",
            description=d.get("description") or "",
            on_master=d["sha"] in idx, master_idx=idx.get(d["sha"]),
        ))
    return sorted(out, key=lambda d: (d.created, d.id))


def contains(deploy: Deploy, master_idx: int | None) -> bool:
    """部署的版本有沒有包含 master 上位置 master_idx 的 commit（直線歷史：比它新或就是它）。"""
    return deploy.on_master and master_idx is not None and deploy.master_idx <= master_idx


def compute(snap: dict, since: datetime | None = None, until: datetime | None = None) -> Result:
    until = until or ts(snap["fetched_at"])
    deploys = load_deploys(snap)
    succ_all = [d for d in deploys if d.state == "success"]
    idx = {sha: i for i, sha in enumerate(snap["master_commits"])}
    issues = {i["number"]: i for i in snap["issues"]}

    def in_window(t: datetime | None) -> bool:
        return t is not None and (since is None or t >= since) and t <= until

    # ── 變更前置時間：PR 第一個 commit（DORA 的起點）／issue 開立（talkcal 多看的）→ 第一個包含它的成功部署
    #    這個環境第一筆部署之前合併的 PR 不算：那時還沒有 CD，「等到 CD 做好才上線」量的不是交付速度
    first_deploy = min((d.created for d in deploys), default=None)
    lead, undeployed, before_cd = [], [], []
    for pr in snap["prs"]:
        mi = idx.get(pr["merge_sha"])
        if mi is None:
            continue   # 不在 master 的直線歷史上（例如合進別的分支），不算
        merged = ts(pr["merged_at"])
        if first_deploy is None or merged < first_deploy:
            before_cd.append(pr)
            continue
        # 包含合併 commit 的部署一定在合併之後，不用再比時間
        dep = next((d for d in sorted(succ_all, key=lambda d: d.finished) if contains(d, mi)), None)
        if dep is None:
            undeployed.append(pr)
            continue
        if not in_window(dep.finished):
            continue
        opened = [ts(issues[n]["created_at"]) for n in pr["closing_issues"] if n in issues]
        lead.append({
            "pr": pr, "deploy": dep,
            "from_commit": dep.finished - ts(pr["first_commit_at"]),
            "from_issue": dep.finished - min(opened) if opened else None,
        })

    # ── 變更失敗：三種訊號，同一個部署只算一次、原因合併
    failures: dict[int, Failure] = {}
    # ── 重工部署（rework）：計畫外、為了修線上問題的成功部署 —— rollback、包含事故修正的、hotfix 分支的。
    #    部署失敗之後的下一次成功不算：那是一般的下一個版本，不是因為線上出事才排的
    rework: dict[int, tuple[Deploy, str]] = {}

    def mark(d: Deploy, reason: str, start: datetime) -> Failure:
        f = failures.setdefault(d.id, Failure(deploy=d))
        f.reasons.append(reason)
        f.start = min(f.start, start) if f.start else start
        return f

    def next_success(after: datetime, pred=lambda d: True) -> Deploy | None:
        return next((d for d in sorted(succ_all, key=lambda d: d.finished) if d.finished > after and pred(d)), None)

    # (a) 部署本身失敗：線上可能停在半套。從這次部署開始算，到下一個成功部署為止
    for d in deploys:
        if d.state in ("failure", "error"):
            f = mark(d, "部署失敗", d.created)
            nxt = next_success(d.finished)
            if nxt and (f.recovered is None or nxt.finished < f.recovered):
                f.recovered, f.recovered_by = nxt.finished, f"部署 {nxt.sha[:7]} 成功"

    # (b) rollback：成功部署了一個比線上更舊、而且以前成功上線過的 master commit。
    #     被退掉的那一版（rollback 前線上那一版）算失敗。同一個 sha 再部署一次是重試（位置一樣、不是「更舊」），不算；
    #     rollback 之後再部署回比較新的版本是往前走，也不算
    live: Deploy | None = None
    seen: set[str] = set()
    for d in sorted(succ_all, key=lambda d: d.finished):
        if (live and d.sha in seen and d.on_master and live.on_master
                and d.master_idx > live.master_idx):
            f = mark(live, f"被 rollback 到 {d.sha[:7]}", live.finished)
            if f.recovered is None or d.finished < f.recovered:
                f.recovered, f.recovered_by = d.finished, f"rollback 到 {d.sha[:7]}"
            rework.setdefault(d.id, (d, f"rollback（退掉 {live.sha[:7]}）"))
        seen.add(d.sha)
        live = d

    # (c) 線上事故 issue（hotfix.md 第 0 步開的）：開 issue 當下線上那一版算失敗。
    #     恢復：之後第一個「包含修正」的成功部署 —— master 上 Closes 它的 PR，或描述寫著 #N 的 hotfix 部署
    incidents = []
    for i in snap["issues"]:
        if INCIDENT_LABEL not in i["labels"] or not i["title"].startswith(INCIDENT_PREFIX):
            continue
        opened = ts(i["created_at"])
        incidents.append(i)
        culprit = next((d for d in sorted(succ_all, key=lambda d: d.finished, reverse=True)
                        if d.finished <= opened), None)
        if culprit is None:
            continue
        f = mark(culprit, f"線上事故 #{i['number']}", opened)
        fix_idx = [idx[p["merge_sha"]] for p in snap["prs"]
                   if i["number"] in p["closing_issues"] and p["merge_sha"] in idx]
        fix = next_success(opened, lambda d: any(contains(d, fi) for fi in fix_idx)
                           or (not d.on_master and f"#{i['number']}" in d.description))
        if fix and (f.recovered is None or fix.finished < f.recovered):
            f.recovered, f.recovered_by = fix.finished, f"部署 {fix.sha[:7]}（修正 #{i['number']}）"
        if fix:
            rework.setdefault(fix.id, (fix, f"修正線上事故 #{i['number']}"))

    # 不在 master 上的成功部署 = 從 hotfix 分支手動部署的，本身就是計畫外
    for d in succ_all:
        if not d.on_master:
            rework.setdefault(d.id, (d, f"不在 master 的部署（hotfix 分支）：{d.description}"))

    win = [d for d in deploys if in_window(d.finished or d.created)]
    attempts = [d for d in win if d.state in TERMINAL]
    return Result(
        window_start=since, window_end=until, deploys=win,
        attempts=attempts,
        successes=[d for d in attempts if d.state == "success"],
        unfinished=[d for d in win if d.state == "unfinished"],
        lead=lead, undeployed=undeployed, before_cd=before_cd,
        failures=sorted((f for f in failures.values() if in_window(f.deploy.finished or f.deploy.created)),
                        key=lambda f: f.deploy.created),
        rework=sorted((r for r in rework.values() if in_window(r[0].finished)), key=lambda r: r[0].finished),
        incidents=[i for i in incidents if in_window(ts(i["created_at"]))],
        first_deploy=first_deploy,
    )


# ───────────────────────── render ─────────────────────────

def fmt_td(td: timedelta | None) -> str:
    if td is None:
        return "—"
    m = td.total_seconds() / 60
    if m < 60:
        return f"{m:.0f} 分"
    if m < 60 * 48:
        return f"{m / 60:.1f} 小時"
    return f"{m / 1440:.1f} 天"


def fmt_t(t: datetime | None) -> str:
    return t.astimezone(TPE).strftime("%m-%d %H:%M") if t else "—"


def median_td(xs: list[timedelta]) -> timedelta | None:
    return timedelta(seconds=statistics.median(x.total_seconds() for x in xs)) if xs else None


def pct(n: int, d: int) -> str:
    return f"{n}/{d} = {n / d:.0%}" if d else "—（沒有部署）"


def render(snap: dict, r: Result) -> str:
    repo, env = snap["repo"], snap["environment"]
    first = r.first_deploy
    # 區間比資料還長時（例如 --days 30 但第一筆部署是 3 天前），從第一筆部署算，不然頻率會被稀釋
    start = max(x for x in (r.window_start, first) if x) if (r.window_start or first) else None
    days = max((r.window_end - start).total_seconds() / 86400, 1) if start else 0
    deploy_days = len({d.finished.astimezone(TPE).date() for d in r.successes})
    lead_c = [x["from_commit"] for x in r.lead]
    lead_i = [x["from_issue"] for x in r.lead if x["from_issue"] is not None]
    recov = [f.recovery for f in r.failures if f.recovery is not None]

    L = [
        f"# 交付指標（DORA）— {repo}，環境 `{env}`",
        "",
        f"區間 {fmt_t(start)} ～ {fmt_t(r.window_end)}（台北時間，約 {days:.1f} 天）。"
        f"資料抓取時間 {fmt_t(ts(snap['fetched_at']))}。定義見 [cd/dora/README.md](https://github.com/{repo}/blob/master/cd/dora/README.md)。",
        "",
        "| 類別 | 指標 | 數值 |",
        "|---|---|---|",
        f"| 速度 | 部署頻率 | 成功 {len(r.successes)} 次（{len({d.sha for d in r.successes})} 個不同版本），每天 {len(r.successes) / days:.1f} 次；有部署的天數 {deploy_days} |" if days else
        "| 速度 | 部署頻率 | — |",
        f"| 速度 | 變更前置時間（第一個 commit → 上線），中位數 | {fmt_td(median_td(lead_c))}（{len(lead_c)} 個 PR）|",
        f"| 速度 | 變更前置時間（issue 開立 → 上線），中位數 | {fmt_td(median_td(lead_i))}（{len(lead_i)} 個有連 issue 的 PR）|",
        f"| 速度 | 失敗部署復原時間，中位數 | {fmt_td(median_td(recov))}（{len(recov)} 次有復原）|",
        f"| 不穩定 | 變更失敗率 | {pct(len(r.failures), len(r.attempts))} |",
        f"| 不穩定 | 部署重工率 | {pct(len(r.rework), len(r.successes))} |",
        "",
        f"部署嘗試 {len(r.attempts)} 次（成功 {len(r.successes)}、失敗 {len(r.attempts) - len(r.successes)}）"
        + (f"；另有 {len(r.unfinished)} 筆沒有結果（只有 in_progress），不列入分母" if r.unfinished else "") + "。",
        f"線上事故 issue（label `{INCIDENT_LABEL}`、標題「{INCIDENT_PREFIX}」開頭）：{len(r.incidents)} 個。",
        "",
        "## 變更失敗明細",
        "",
    ]
    if r.failures:
        L += ["| 部署 | 版本 | 為什麼算失敗 | 開始 | 恢復 | 復原時間 | 怎麼恢復的 |", "|---|---|---|---|---|---|---|"]
        for f in r.failures:
            d = f.deploy
            L.append(f"| {d.id} | `{d.sha[:7]}` | {'、'.join(f.reasons)} | {fmt_t(f.start)} | {fmt_t(f.recovered)} "
                     f"| {fmt_td(f.recovery)} | {f.recovered_by or '還沒恢復'} |")
    else:
        L.append("沒有。")
    L += ["", "## 重工部署明細", ""]
    if r.rework:
        L += ["| 部署 | 版本 | 完成 | 原因 |", "|---|---|---|---|"]
        L += [f"| {d.id} | `{d.sha[:7]}` | {fmt_t(d.finished)} | {why} |" for d, why in r.rework]
    else:
        L.append("沒有。")
    L += ["", "## 變更前置時間明細", "",
          "| PR | 第一個 commit | 合併 | 上線（部署完成） | commit→上線 | issue→上線 |", "|---|---|---|---|---|---|"]
    for x in sorted(r.lead, key=lambda x: x["deploy"].finished):
        p = x["pr"]
        L.append(f"| #{p['number']} {p['title'][:40]} | {fmt_t(ts(p['first_commit_at']))} | {fmt_t(ts(p['merged_at']))} "
                 f"| {fmt_t(x['deploy'].finished)} `{x['deploy'].sha[:7]}` | {fmt_td(x['from_commit'])} | {fmt_td(x['from_issue'])} |")
    if r.before_cd:
        L += ["", f"這個環境第一筆部署之前合併、不算前置時間的 PR：{len(r.before_cd)} 個"]
    if r.undeployed:
        L += ["", f"已合併、還沒有成功部署包含它：{', '.join('#%d' % p['number'] for p in r.undeployed)}"]
    return "\n".join(L) + "\n"


# ───────────────────────── main ─────────────────────────

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--repo", default="Anderson-chen/talkcal")
    ap.add_argument("--env", default="local", help="GitHub Deployments 的 environment（github-pages 不是線上服務，不算）")
    ap.add_argument("--days", type=int, help="只看最近幾天；不給就是全部歷史")
    ap.add_argument("--snapshot", help="不連 GitHub，讀這份 JSON")
    ap.add_argument("--save-snapshot", help="把撈到的原始資料存成 JSON")
    ap.add_argument("--out", help="報告寫到這個檔案；不給就印到 stdout")
    a = ap.parse_args(argv)

    if a.snapshot:
        with open(a.snapshot, encoding="utf-8") as f:
            snap = json.load(f)
    else:
        snap = fetch(a.repo, a.env)
    if a.save_snapshot:
        with open(a.save_snapshot, "w", encoding="utf-8") as f:
            json.dump(snap, f, ensure_ascii=False, indent=1)

    until = ts(snap["fetched_at"])
    since = until - timedelta(days=a.days) if a.days else None
    report = render(snap, compute(snap, since, until))
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(report)
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
