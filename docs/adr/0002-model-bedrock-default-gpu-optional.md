# 0002. 模型預設走 Bedrock，GPU 節點群組可選、平常 0 台

- 狀態：已採用
- 日期：2026-10-16
- 相關：[#58](https://github.com/Anderson-chen/talkcal/issues/58)、[#72 GPU 配額被拒、改走 Bedrock](https://github.com/Anderson-chen/talkcal/issues/72)、[#35 模型成本](https://github.com/Anderson-chen/talkcal/issues/35)

## 背景

原本的規劃（2-eks README 第一版）是 GPU 節點（g6 L4）跑 llama-server，模型從 S3 拉進 EBS。
2026-10-13 申請東京 G 系列配額被拒（新帳號預設 0 vCPU，AWS 要「逐步增加用量」），階段一因此改走 Bedrock，2026-10-14 上線驗證過：
app 用 Spring AI 的 OpenAI client 打 `bedrock-mantle` 的 Chat Completions，Qwen3 32B 的 `json_schema` 輸出和工具呼叫都能用。

規劃表裡還有兩題：「模型自己架還是用託管」和「GPU 節點要不要一直開」。

## 選項

| 選項 | 每小時 | 好處 | 代價 |
|---|---|---|---|
| Bedrock（託管 API） | 不叫不收，按 token 計 | 不吃 GPU 配額、不用管驅動和模型檔；階段一已驗證 | 跟本機不是同一顆模型（32B vs 8B）；要保管一把 API key |
| GPU 節點常駐 | g6.xlarge 約 0.8–1 USD（東京） | 跟本機同一個映像檔、同一顆模型 | 配額被拒，現在開不出來；就算開得出來，一個月 600–700 USD，是其他所有東西的好幾倍 |
| GPU 節點、沒人用時縮到 0 | 開著時同上 | 不用時不收 | 第一個請求要等開機 + 拉模型 + 載入（分鐘級）；要另外做「有請求才擴」的機制（KEDA、Karpenter） |

## 決定

**Terraform 變數 `model_backend` 沿用 1-ec2 的開關，預設 `bedrock`；設成 `gpu` 才建 GPU 節點群組、模型 bucket、llama-server 的 ECR repo。**

- `bedrock`：app 的 Deployment 用環境變數把模型位址、key、名稱換成 Bedrock 的（跟 1-ec2 的 `compose.bedrock.yaml` 同一招），程式不用改。
  key 跟 1-ec2 一樣綁在只能呼叫模型的專用 IAM user 上、90 天過期，存進 Secrets Manager，由 External Secrets 交給 Pod。
- `gpu`：一個 `g6.xlarge` 的節點群組，`min=0`，`desired` 由變數 `gpu_node_count` 決定（預設 1）。
  AMI 用 Bottlerocket NVIDIA：驅動和 NVIDIA device plugin 都內建，不必自己裝 GPU Operator。節點帶 `nvidia.com/gpu` taint，一般 Pod 不會排上去。
  模型檔放 S3，initContainer 用 Pod Identity 讀；Pod 怎麼寫是 #56 `components/llm-chat-gpu` 的事，這裡只準備權限和節點。
- 「縮到 0」先用手動的：`terraform apply -var gpu_node_count=0`。有了真實流量再考慮自動擴縮。

## 代價

- 預設的雲端環境沒有跑本機那顆模型；「同一份映像檔原樣搬上去」在模型這一塊不成立。
- 多一把長期 key 要輪替（`terraform apply -replace=...`，跟 1-ec2 一樣）。

## 什麼時候重想

- GPU 配額下來 → `model_backend=gpu` 驗證一次，跟 Bedrock 比延遲和每次問答的成本（#35）。
- 想拿掉長期 key → 改用 Spring AI 的 Bedrock Converse 模組，Pod 直接用 Pod Identity 拿臨時憑證。
