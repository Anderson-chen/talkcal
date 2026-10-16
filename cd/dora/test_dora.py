"""dora.py 的反例測試：用造出來的交付紀錄，證明該算失敗的會被算、正常的不會被誤算。

    python -m unittest discover -s cd/dora -v

每個情境自己造一份 snapshot（跟 fetch 存下來的格式一樣），時間都用「第幾分鐘」好對答案。
"""

import unittest
from datetime import datetime, timedelta, timezone

from dora import compute, render

T0 = datetime(2026, 10, 1, tzinfo=timezone.utc)
# master 上的 commit，新的在前（跟 GitHub commits API 一樣）
MASTER = ["c5", "c4", "c3", "c2", "c1"]


def at(minute: int) -> str:
    return (T0 + timedelta(minutes=minute)).isoformat().replace("+00:00", "Z")


def dep(id, sha, start, end, state="success", env="local", description="cd/triggers/watch.sh"):
    """一筆部署：start 分鐘開始，end 分鐘到終態。end=None 表示只有 in_progress。"""
    statuses = [{"state": "in_progress", "created_at": at(start)}]
    if end is not None:
        statuses.insert(0, {"state": state, "created_at": at(end)})   # API 是新的在前
    return {"id": id, "sha": sha, "environment": env, "created_at": at(start),
            "description": description, "statuses": statuses}


def pr(number, merge_sha, first_commit, merged, closes=()):
    return {"number": number, "title": f"PR {number}", "merged_at": at(merged), "merge_sha": merge_sha,
            "first_commit_at": at(first_commit), "closing_issues": list(closes)}


def issue(number, title, created, labels=("bug",)):
    return {"number": number, "title": title, "created_at": at(created), "closed_at": None, "labels": list(labels)}


def snap(deployments, prs=(), issues=(), fetched=10_000):
    return {"repo": "o/r", "environment": "local", "fetched_at": at(fetched), "master_commits": MASTER,
            "deployments": list(deployments), "prs": list(prs), "issues": list(issues)}


def run(s):
    r = compute(s)
    render(s, r)   # 每個情境都順便確認報告印得出來
    return r


class NormalDeploys(unittest.TestCase):
    """反例的對照組：一路成功、往前走，什麼都不該被算成失敗或重工。"""

    def test_forward_deploys_are_not_failures(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62), dep(3, "c3", 120, 122)]))
        self.assertEqual(len(r.successes), 3)
        self.assertEqual(r.failures, [])
        self.assertEqual(r.rework, [])

    def test_redeploying_the_live_sha_is_a_retry_not_a_rollback(self):
        # watcher 設定期間同一個 commit 部署好幾次（真實歷史 10-13 的 01eea36）
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c1", 10, 12), dep(3, "c2", 60, 62)]))
        self.assertEqual(r.failures, [])
        self.assertEqual(r.rework, [])

    def test_ordinary_bug_issue_is_not_an_incident(self):
        # label bug、但不是「線上：」開頭：開發中發現的 bug，不是線上事故
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62)], issues=[issue(9, "登入頁錯字", 70)]))
        self.assertEqual(r.failures, [])
        self.assertEqual(r.incidents, [])

    def test_online_titled_issue_without_bug_label_is_not_an_incident(self):
        r = run(snap([dep(1, "c1", 0, 2)], issues=[issue(9, "線上：想加深色模式", 10, labels=("enhancement",))]))
        self.assertEqual(r.failures, [])

    def test_other_environments_and_unfinished_are_not_counted(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c1", 5, 6, env="github-pages", state="failure"),
                      dep(3, "c2", 60, None)]))
        self.assertEqual([d.id for d in r.attempts], [1])          # pages 不算、沒結果的不進分母
        self.assertEqual([d.id for d in r.unfinished], [3])
        self.assertEqual(r.failures, [])


    def test_frequency_uses_first_deploy_when_window_is_longer_than_history(self):
        # 第一筆部署在第 0 分鐘、抓取在第 2 天：--days 30 的頻率要除以 2 天，不是 30 天
        s = snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62)], fetched=2 * 1440)
        report = render(s, compute(s, since=T0 - timedelta(days=28)))
        self.assertIn("每天 1.0 次", report)


class FailedDeploy(unittest.TestCase):
    def test_failed_deploy_counts_and_recovers_at_next_success(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 65, state="failure"), dep(3, "c3", 120, 130)]))
        self.assertEqual([f.deploy.id for f in r.failures], [2])
        self.assertEqual(r.failures[0].reasons, ["部署失敗"])
        self.assertEqual(r.failures[0].recovery, timedelta(minutes=130 - 60))   # 部署開始 → 下一次成功完成
        self.assertEqual(len(r.attempts), 3)
        # 失敗後的下一個版本是平常的下一次部署，不是因為出事才排的
        self.assertEqual(r.rework, [])

    def test_never_recovered(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 65, state="error")]))
        self.assertEqual(len(r.failures), 1)
        self.assertIsNone(r.failures[0].recovery)


class Rollback(unittest.TestCase):
    def test_rollback_marks_the_rolled_back_version(self):
        # c2 上線後出事，退回 c1（以前成功上線過、比 c2 舊）
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62), dep(3, "c1", 90, 93)]))
        self.assertEqual([f.deploy.id for f in r.failures], [2])
        self.assertIn("rollback", r.failures[0].reasons[0])
        self.assertEqual(r.failures[0].recovery, timedelta(minutes=93 - 62))    # c2 上線 → 退回完成
        self.assertEqual([d.id for d, _ in r.rework], [3])

    def test_rollback_with_incident_issue_starts_clock_at_issue(self):
        # 有事故 issue：開始時間用開 issue 的時間（比「上線時間」準）
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62), dep(3, "c1", 90, 93)],
                     issues=[issue(9, "線上：登入 500", 80)]))
        self.assertEqual(len(r.failures), 1)                                     # 同一個部署只算一次
        f = r.failures[0]
        self.assertEqual(len(f.reasons), 2)
        self.assertEqual(f.start, T0 + timedelta(minutes=62))                    # 取兩個訊號裡早的那個
        self.assertEqual(f.recovery, timedelta(minutes=93 - 62))

    def test_redeploying_forward_after_rollback_is_not_another_rollback(self):
        # 退回 c1 之後再部署 c2（c2 以前上線過，但比線上新）：是往前走，不是又一次 rollback
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62), dep(3, "c1", 90, 93), dep(4, "c2", 150, 152)]))
        self.assertEqual([f.deploy.id for f in r.failures], [2])
        self.assertEqual([d.id for d, _ in r.rework], [3])

    def test_deploying_an_older_never_deployed_sha_is_not_a_rollback(self):
        # c1 從沒成功上線過：部署它不是「退回已知好的版本」
        r = run(snap([dep(1, "c2", 0, 2), dep(2, "c3", 60, 62), dep(3, "c1", 90, 93)]))
        self.assertEqual(r.failures, [])


class Incident(unittest.TestCase):
    def test_incident_fixed_through_master(self):
        # c2 上線 → 開「線上：」issue #9 → PR #20（merge c3）Closes #9 → c3 部署
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62), dep(3, "c3", 200, 205)],
                     prs=[pr(20, "c3", 110, 190, closes=[9])],
                     issues=[issue(9, "線上：行程存不進去", 100)]))
        self.assertEqual([f.deploy.id for f in r.failures], [2])                 # 開 issue 當下線上的那一版
        self.assertEqual(r.failures[0].recovery, timedelta(minutes=205 - 100))
        self.assertEqual([d.id for d, _ in r.rework], [3])
        self.assertEqual(len(r.attempts), 3)

    def test_unrelated_deploy_after_incident_does_not_count_as_recovery(self):
        # issue 開了之後部署的 c3 沒有包含修正（修正在 c4），恢復要等到 c4
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c3", 150, 152), dep(3, "c4", 300, 302)],
                     prs=[pr(20, "c4", 120, 290, closes=[9]), pr(21, "c3", 100, 140)],
                     issues=[issue(9, "線上：行程存不進去", 100)]))
        self.assertEqual([f.deploy.id for f in r.failures], [1])
        self.assertEqual(r.failures[0].recovery, timedelta(minutes=302 - 100))
        self.assertEqual([d.id for d, _ in r.rework], [3])                       # c3 是平常的部署

    def test_incident_fixed_by_hotfix_branch(self):
        # 情境 B：master 不能上線，從 hotfix 分支手動部署（sha 不在 master），描述寫 #9
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 62),
                      dep(3, "h1", 130, 135, description="hotfix #9（手動）")],
                     issues=[issue(9, "線上：行程存不進去", 100)]))
        self.assertEqual([f.deploy.id for f in r.failures], [2])
        self.assertEqual(r.failures[0].recovery, timedelta(minutes=135 - 100))
        self.assertEqual([d.id for d, _ in r.rework], [3])


class LeadTime(unittest.TestCase):
    def test_lead_time_from_first_commit_and_from_issue(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c3", 100, 110)],
                     prs=[pr(20, "c2", 30, 50, closes=[7]), pr(21, "c3", 60, 90)],
                     issues=[issue(7, "加功能", 10, labels=("enhancement",))]))
        lead = {x["pr"]["number"]: x for x in r.lead}
        # c2 自己沒被部署，但 c3 包含它：上線時間是 c3 部署完成
        self.assertEqual(lead[20]["from_commit"], timedelta(minutes=110 - 30))
        self.assertEqual(lead[20]["from_issue"], timedelta(minutes=110 - 10))
        self.assertIsNone(lead[21]["from_issue"])

    def test_merged_before_first_deploy_and_not_yet_deployed(self):
        r = run(snap([dep(1, "c2", 100, 110)],
                     prs=[pr(19, "c1", 0, 10), pr(20, "c3", 120, 130)]))
        self.assertEqual(r.lead, [])
        self.assertEqual([p["number"] for p in r.before_cd], [19])   # CD 還不存在時合併的
        self.assertEqual([p["number"] for p in r.undeployed], [20])

    def test_failed_deploy_does_not_count_as_shipped(self):
        r = run(snap([dep(1, "c1", 0, 2), dep(2, "c2", 60, 65, state="failure"), dep(3, "c3", 100, 104)],
                     prs=[pr(20, "c2", 20, 50)]))
        self.assertEqual(r.lead[0]["from_commit"], timedelta(minutes=104 - 20))


if __name__ == "__main__":
    unittest.main()
