# infra —— 搬上 AWS 的基礎設施

狀態：階段一的 Terraform 寫好了、還沒 apply 過；階段二還在規劃。本機現在怎麼跑見 [docs/architecture.md](../docs/architecture.md)。

## 兩個階段，一個資料夾一個

```
infra/
├── README.md          # 這份：階段怎麼分、共同的慣例
├── bootstrap/         # 兩階段共用、整個帳號只跑一次：放 state 的 S3 bucket、花費告警
└── envs/
    ├── 1-ec2/         # 階段一：一台 GPU EC2 + Docker Compose（#57），Terraform 已寫好
    └── 2-eks/         # 階段二：EKS 叢集（#58），只有規劃
```

| 階段 | 資料夾 | 追蹤 | 換來什麼 | 代價 |
|---|---|---|---|---|
| 一 | [envs/1-ec2](envs/1-ec2/README.md) | [#57](https://github.com/Anderson-chen/talkcal/issues/57) | 服務真的在線上，別人連得到；compose 幾乎不用改 | 只有一台，掛了就停，換版時有空窗 |
| 二 | [envs/2-eks](envs/2-eks/README.md) | [#58](https://github.com/Anderson-chen/talkcal/issues/58) | 換版不停機、一台掛了不會全停，staging / rollback / canary 有地方練 | 元件多、要先學 k8s（[#56](https://github.com/Anderson-chen/talkcal/issues/56) 本機 kind） |

**為什麼先做階段一**：上去的就是本機驗證過的那一套，要補的只有對外的入口和祕密。一次把 k8s、EKS、RDS、CloudFront 全換掉，出問題時分不出是哪一層。

## 慣例

照 cool-down 專案的做法（它從 ECS Fargate 換成單台 EC2，兩套都留在 `infra/envs/` 底下）：

| 慣例 | 為什麼 |
|---|---|
| 一個階段一個資料夾，各自是獨立的 Terraform 根目錄、state key 分開 | 拆掉一個不會動到另一個；兩階段可以短暫並存，切換時對照 |
| 資料夾名稱加數字前綴（`1-`、`2-`） | 列出來就是做的順序 |
| 每個資料夾都有自己的 README：架構圖、要改的地方、首次部署、日常操作、省錢、疑難排解 | 部署怎麼做跟部署設定放在一起，改設定時文件就在旁邊 |
| 機器上跑的 compose 沿用 [deploy/compose.yaml](../deploy/compose.yaml)，雲端才需要的差異放各階段資料夾（例如 compose override、Caddyfile） | 本機和雲端是同一份定義，不會分岔。cool-down 是另寫一份正式用的 compose，這裡不跟 |
| 某個階段拆掉了，程式碼保留，README 開頭註明拆除日期 | 留著當參考；cool-down 的 `envs/learning` 就是這樣 |

`bootstrap/` 用本機 state（state bucket 還不存在，沒地方放），所以只放這兩樣東西，其他資源一律放各階段的資料夾。

## 待決定

各階段的「待決定」寫在各自的 README。選好之後理由寫進 ADR（[#47](https://github.com/Anderson-chen/talkcal/issues/47)）。
