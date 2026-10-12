# 分類方法與參考資料

[← 回上線準備清單](../production-readiness.md)

## 怎麼分類

一個面向接一個面向地列，很容易漏掉東西，所以照產品的生命週期分成五段。被問到「上線要注意什麼」時，照這五段走一輪，就不會整塊漏掉。

```
① 能不能上線    ② 怎麼上線      ③ 跑起來之後     ④ 出事的時候     ⑤ 長期活下去
  Ready           Release          Run              Respond          Evolve
  測試/資安/設定   CI/CD/部署/回退   觀測/容量/成本    告警/除錯/復原    升級/資料/文件
                  開發流程
```

五段的分法是自己整理的。裡面的項目拿下面這幾份業界清單逐條對照過（2026-10-08），對方有、這裡沒有的都補進來了。

## 參考資料

- [Google SRE Book：Launch Coordination Checklist](https://sre.google/sre-book/launch-checklist/)：Google 上線前逐項確認的清單，涵蓋容量、故障演練、相依服務、漸進式發布、監控監控本身
- [Mercari Production Readiness Checklist](https://github.com/mercari/production-readiness-checklist)：公開在 GitHub 上、實際在用的版本。分成 Maintainability、Observability、Reliability、Security、Data Storage，依 SLO 分等級決定要做到哪些
- [AWS Well-Architected Generative AI Lens](https://docs.aws.amazon.com/wellarchitected/latest/generative-ai-lens/generative-ai-lens.html)：把六根支柱套在生成式 AI 的生命週期上（選模型、prompt、整合、部署、持續改進）
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/)、[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)：LLM 和 agent 特有的資安風險。詳細內容裡 `LLM01` 這類編號指的就是這兩份
- [trunkbaseddevelopment.com](https://trunkbaseddevelopment.com/)、[Git Flow 原文與作者 2020 年的補註](https://nvie.com/posts/a-successful-git-branching-model/)：分支策略和 hotfix 該怎麼選
- [AGENTS.md](https://agents.md/)、[DORA](https://dora.dev/)：用 AI 寫程式時，規則怎麼跟著 repo 走、怎麼量 AI 對交付的影響
- [Atlassian：Integrate Jira with GitHub](https://support.atlassian.com/jira-cloud-administration/docs/integrate-with-github/)：issue key 寫進分支、commit、PR，Jira 就會把開發進度連回 issue
- [HikariCP：Dropwizard / Micrometer metrics](https://github.com/brettwooldridge/HikariCP/wiki/Dropwizard-Metrics)、[Spring Boot：Supported metrics（DataSource）](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)：連線池有哪些指標可以看
- [Grafana：Trace correlations](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)、[Tempo：metrics-generator（service graph）](https://grafana.com/docs/tempo/latest/metrics-generator/service_graphs/)：在 Grafana 裡把指標、log、trace 串起來
