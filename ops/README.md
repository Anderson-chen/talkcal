# ops —— 觀測環境（Observability）

用 Docker 一次拉起 **Grafana + Prometheus + Loki**，跟 `src/` 的 Java app 分開。
app 照常跑在你機器上（`./gradlew bootRun`），這裡的容器只負責「收資料 + 畫圖」。

## 三個角色

| 服務 | 做什麼 | 對外埠 | 看它 |
|------|--------|--------|------|
| **Grafana** | 畫圖前台，自己不存資料，去跟下面兩個要資料 | 3000 | <http://localhost:3000> |
| **Prometheus** | 存**指標(metrics)**：每隔 15 秒去抓 app 吐的數字 | 9090 | <http://localhost:9090> |
| **Loki** | 存**日誌(logs)**：集中放 log，在 Grafana 同一介面查 | 3100 | （沒有 UI，靠 Grafana 查）|

Grafana 一啟動就自動接好 Prometheus、Loki 兩個資料源（見 `grafana/provisioning/`），
開網頁不必登入，直接是 Admin（純本機學習用，刻意關掉登入）。

## 怎麼跑

```bash
cd ops
docker compose up -d        # 背景拉起三個容器
docker compose ps           # 看狀態
docker compose logs -f      # 追 log（Ctrl+C 離開，不會關容器）
docker compose down         # 關掉（資料留著）
docker compose down -v      # 關掉並清空資料，整組重來
```

## 現在是什麼狀態

- **Grafana、Prometheus、Loki 三個都會起來、互相接通**（資料源綠燈）。
- 但**還沒有 app 的資料**：
  - Prometheus 的 `eat-app` 抓取目標會是**紅的(DOWN)**——因為 app 還沒有 web server / metrics 端點。**這是正常的**，環境本身沒問題。
  - Loki 裡查不到 log——因為還沒有人往它送 log。同樣正常。

換句話說，這一步只把「空的觀測環境」立起來、等資料進來。

## 下一步（之後才做，一次一件）

1. **讓 app 吐指標**：替 app 加 `spring-boot-starter-web` + `spring-boot-starter-actuator` + `micrometer-registry-prometheus`，
   打開 `/actuator/prometheus`。這裡的 `prometheus/prometheus.yml` **不用改**，`eat-app` 目標會自己變綠。
2. **讓 log 進 Loki**：加一個 log 收集器（Promtail 或 Grafana Alloy）把 app 的 log 送進 Loki。
3. 進 Grafana 建第一個 dashboard，或匯入現成的 JVM / Spring Boot 儀表板。

## 檔案結構

```
ops/
├── compose.yaml                              # 三個服務的定義
├── prometheus/prometheus.yml                 # 抓誰、多久抓一次
├── loki/loki-config.yaml                     # 單機最小設定
└── grafana/provisioning/datasources/
    └── datasources.yaml                      # 自動接好 Prometheus + Loki
```
