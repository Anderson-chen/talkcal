# 0010. 上 AWS 分兩階段；階段一是一台 EC2 跑同一份 compose，模型先走 Bedrock

- 狀態：已採用（2026-10-14 部署上線）
- 日期：2026-10-13
- 相關：[5d693ea 規劃搬上 AWS 的兩個階段](https://github.com/Anderson-chen/talkcal/commit/5d693ea)、[83a9ef2 雲端先走一般機型 + Bedrock](https://github.com/Anderson-chen/talkcal/commit/83a9ef2)、[#57](https://github.com/Anderson-chen/talkcal/issues/57)、[#72](https://github.com/Anderson-chen/talkcal/issues/72)、階段二見 [infra/envs/2-eks](../../infra/envs/2-eks/README.md)

## 背景

本機已經是兩組 Docker Compose（`deploy/` 被觀測的服務、`ops/` 觀測工具），驗證過能一鍵起來、看得到三種訊號。
要搬上 AWS，讓別人連得到。2026-10-13 申請東京區 G 系列 GPU 配額（新帳號預設 0）被拒，AWS 要新帳號「逐步增加用量」；一般機型的配額已經有 32 vCPU。

## 選項

怎麼搬：

| 選項 | 好處 | 代價 |
|---|---|---|
| 一次上 EKS（k8s、RDS、CloudFront 全換） | 一步到位 | 出問題時分不出是哪一層 |
| 先一台 EC2 跑同一份 compose，再上 EKS | 上去的就是本機驗證過的那一套，只要補對外的入口和祕密 | 只有一台，掛了就停、換版有空窗 |

模型怎麼跑（GPU 開不出來之後）：

| 選項 | 代價 |
|---|---|
| 等 GPU 配額（g6.xlarge 跑同一份 llama-server） | 不知道要等多久 |
| 一般機型 + Bedrock（OpenAI 相容的 `bedrock-mantle` 端點） | 跟本機不是同一顆模型（Qwen3 32B vs 8B）；多一把要保管的 API key |

## 決定

**兩個階段、一個資料夾一個（`infra/envs/1-ec2`、`infra/envs/2-eks`）。階段一 Terraform 變數 `model_backend` 預設 `bedrock`：m7i-flex.large，app 打 Bedrock 的 Qwen3 32B；設成 `gpu` 就回到 g6.xlarge + llama-server。**

app 一行不用改：因為跟模型講話走 OpenAI 相容協定（[0001](0001-spring-ai-openai-compatible.md)），`compose.bedrock.yaml` 只用環境變數換位址、key、模型名稱，並把 `llm-chat`、`model-seed` 放進不會啟用的 profile。
key 綁在只能呼叫模型的專用 IAM user 上、90 天過期，跟 DB 密碼一起寫進 SSM 的 `.env`。

## 代價

- 單台：掛了就停、換版時有空窗（階段二要解的問題）。
- 雲端和本機的模型不同，本機量到的品質和延遲不能直接套到雲端。
- 一把長期 API key 要輪替。
- EC2 目前是手動部署（SSM 連進去重跑 `deploy.sh`），還沒收進 `cd/` 的 watcher（[cd/README.md](../../cd/README.md) 的「還沒做的」）。

## 什麼時候重想

- GPU 配額下來 → `terraform apply -var model_backend=gpu`，跟 Bedrock 比延遲和成本。
- 想拿掉長期 key → 改用 Spring AI 的 Bedrock Converse 模組，直接吃 EC2 的角色。

## 證據

- [infra/README.md](../../infra/README.md)：兩個階段、為什麼先做階段一
- [infra/envs/1-ec2/README.md](../../infra/envs/1-ec2/README.md)：為什麼先走 Bedrock、跟本機的差異表
- [infra/envs/1-ec2/compose.bedrock.yaml](../../infra/envs/1-ec2/compose.bedrock.yaml)、[bedrock.tf](../../infra/envs/1-ec2/bedrock.tf)、[variables.tf](../../infra/envs/1-ec2/variables.tf)
- [AWS 階段一互動架構圖](https://anderson-chen.github.io/talkcal/)
