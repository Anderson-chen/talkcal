# 上線準備怎麼分類

開 issue 時貼哪個 `stage:` label 照這份判斷（規則見 [.claude/rules/issue.md](../.claude/rules/issue.md)）。產品範圍的 `product:` label 怎麼選見 [README 的「產品範圍怎麼分類」](../README.md#產品範圍怎麼分類)。

上線之後要顧慮的事照產品的生命週期分成五段，每一段是一個 epic issue，裡面是所有面向的總表：
還沒做完的面向各有子 issue（sub-issue），現況、做法和證據寫在子 issue 裡，狀態以子 issue 的開關為準；已經完成的面向，說明留在 epic。
被問到「上線要注意什麼」時，照這五段走一輪，就不會整塊漏掉。

```
① 能不能上線    ② 怎麼上線      ③ 跑起來之後     ④ 出事的時候     ⑤ 長期活下去
  Ready           Release          Run              Respond          Evolve
  測試/資安/設定   CI/CD/部署/回退   觀測/容量/成本    告警/除錯/復原    升級/資料/文件
                  開發流程
```

| 階段 | epic |
|---|---|
| ① 能不能上線（Ready） | [#8](https://github.com/Anderson-chen/talkcal/issues/8) |
| ② 怎麼上線（Release） | [#17](https://github.com/Anderson-chen/talkcal/issues/17) |
| ③ 跑起來之後（Run） | [#30](https://github.com/Anderson-chen/talkcal/issues/30) |
| ④ 出事的時候（Respond） | [#38](https://github.com/Anderson-chen/talkcal/issues/38) |
| ⑤ 長期活下去（Evolve） | [#46](https://github.com/Anderson-chen/talkcal/issues/46) |

## 分段的依據

五段照**什麼時候最需要它**來切，不照哪個團隊負責、也不照用了哪種技術。這條時間軸跟兩個業界框架的分段對得上，這裡只是把每一段寫成一個問句，判斷時比較好用：

| 段 | 判斷問題（答「是」就放這段） | AWS Well-Architected 營運卓越支柱 | ITIL v3 服務生命週期 |
|---|---|---|---|
| ① Ready | 少了它，這個版本就不該給使用者用嗎？（版本本身對不對、安不安全） | Prepare：Design for operations、Operational readiness | Service Design、Service Transition 的 Validation and Testing |
| ② Release | 它管的是一個改動從開 issue、寫程式、審查到上線、退版的路嗎？ | Prepare：Mitigate deployment risks | Service Transition |
| ③ Run | 沒出事的平常日也要一直看、一直付的嗎？ | Operate：Utilizing workload observability、Understanding operational health | Service Operation（監控） |
| ④ Respond | 出事的那一刻才用得到嗎？ | Operate：Responding to events | Service Operation（事故、問題管理） |
| ⑤ Evolve | 就算不出事，放著不管也會隨時間變糟，或換人、換電腦就接不下去嗎？ | Evolve | Continual Service Improvement |

ITIL 4 已經把 v3 的五段改成不分先後的價值鏈，這裡對照 v3，是因為它的分段最常被拿來講服務的生命週期。

為什麼不像 Mercari 那樣照屬性（Observability、Security…）分：被問到「上線要注意什麼」時，照時間走一輪，比較不會整塊漏掉。改到哪塊技術（CI、前端、資料庫…）改用 `area:` label 標，一張 issue 可以貼好幾個（見 [`.claude/rules/issue.md`](../.claude/rules/issue.md)）。

一件事碰到好幾段時：

1. 選**最需要它的那一刻**。例如備份平常就在跑，但真正用到是出事的時候，放 ④（#45）；套件升級要過一段時間才會痛，放 ⑤（#48）。
2. 同一件事有「平常就要有」和「出事時才要做」兩半，就拆成兩張。例如觀測放 ③（#31），告警放 ④（#39）：看得到跟會被叫醒是兩件事。
3. 一張 issue 只放一段（只貼一個 `stage:` label）。
4. 開發流程（issue、分支、審查、CI、給 AI 的規則）是改動必經的路，放 ②；讓之後的人看得懂、跑得起來的（README、ADR、本機環境），放 ⑤。

下面這幾份清單不是用來切段，是用來檢查項目有沒有漏：逐條對照過（2026-10-08），對方有、這裡沒有的都補進來了。

## 參考資料

- [AWS Well-Architected：Operational Excellence Pillar](https://docs.aws.amazon.com/wellarchitected/latest/operational-excellence-pillar/welcome.html)：營運分成 Prepare、Operate、Evolve，Operate 底下再分平常看健康和處理事件，是五段分法的主要對照
- [ITIL v3 服務生命週期](https://en.wikipedia.org/wiki/ITIL)：Strategy、Design、Transition、Operation、Continual Improvement 五段（ITIL 4 已改成價值鏈）
- [Google SRE Book：Launch Coordination Checklist](https://sre.google/sre-book/launch-checklist/)：Google 上線前逐項確認的清單，涵蓋容量、故障演練、相依服務、漸進式發布、監控監控本身
- [Mercari Production Readiness Checklist](https://github.com/mercari/production-readiness-checklist)：公開在 GitHub 上、實際在用的版本。分成 Maintainability、Observability、Reliability、Security、Data Storage，依 SLO 分等級決定要做到哪些
- [AWS Well-Architected Generative AI Lens](https://docs.aws.amazon.com/wellarchitected/latest/generative-ai-lens/generative-ai-lens.html)：把六根支柱套在生成式 AI 的生命週期上（選模型、prompt、整合、部署、持續改進）
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/)、[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)：LLM 和 agent 特有的資安風險。epic 裡 `LLM01` 這類編號指的就是這兩份
- [trunkbaseddevelopment.com](https://trunkbaseddevelopment.com/)、[Git Flow 原文與作者 2020 年的補註](https://nvie.com/posts/a-successful-git-branching-model/)：分支策略和 hotfix 該怎麼選
- [AGENTS.md](https://agents.md/)、[DORA](https://dora.dev/)：用 AI 寫程式時，規則怎麼跟著 repo 走、怎麼量 AI 對交付的影響
- [GitHub：Linking a pull request to an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)、[Projects 內建自動化](https://docs.github.com/en/issues/planning-and-tracking-with-projects/automating-your-project/using-the-built-in-automations)：`Closes #N` 怎麼讓合併自動關 issue、看板怎麼自己轉狀態
- [Atlassian：Integrate Jira with GitHub](https://support.atlassian.com/jira-cloud-administration/docs/integrate-with-github/)：issue key 寫進分支、commit、PR，Jira 就會把開發進度連回 issue（原本的規劃，後來改用 GitHub Issues）
- [HikariCP：Dropwizard / Micrometer metrics](https://github.com/brettwooldridge/HikariCP/wiki/Dropwizard-Metrics)、[Spring Boot：Supported metrics（DataSource）](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)：連線池有哪些指標可以看
- [Grafana：Trace correlations](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)、[Tempo：metrics-generator（service graph）](https://grafana.com/docs/tempo/latest/metrics-generator/service_graphs/)：在 Grafana 裡把指標、log、trace 串起來
