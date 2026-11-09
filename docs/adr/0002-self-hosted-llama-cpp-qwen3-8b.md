# 0002. 模型自己架：llama.cpp 跑 Qwen3-8B（Q4_K_M），映像檔自己 build

- 狀態：已採用（本機）；雲端改走 Bedrock，見 [0010](0010-aws-stage-one-ec2-compose-bedrock.md)
- 日期：2026-09-21（第一個 llama.cpp adapter）、2026-09-30（搬進容器）
- 相關：[8f5ae30](https://github.com/Anderson-chen/talkcal/commit/8f5ae30)、[04ce407 模型伺服器搬進容器](https://github.com/Anderson-chen/talkcal/commit/04ce407)、[0001](0001-spring-ai-openai-compatible.md)

## 背景

這個專案要學的是「LLM 串進一個產品之後，上線要顧慮什麼」，模型本身也要能觀測、壓測、部署。
手上有一張 12GB VRAM 的顯卡（[deploy/compose.yaml](../../deploy/compose.yaml) 的註解）。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| 呼叫雲端 API | 不用顧 GPU、模型更大 | 資料出機器、按 token 收費；模型那一段看不到也壓不了 |
| 自架 llama.cpp（GGUF） | 資料不出機器、沒有 token 費用；單人延遲好；有 `/metrics` 可以接進 Grafana | 要自己顧 GPU、驅動、模型檔；8B 的能力有限 |
| 自架 vLLM | 多人同時打的吞吐量好（業界做多人 API 多半選它） | 單人學習用的情境用不到它的強項（[架構互動圖](../architecture/index.html) 的 llm-chat 節點） |

映像檔的來源也比較過：

| 選項 | 實測 |
|---|---|
| 官方 `ghcr.io/ggml-org/llama.cpp:server-cuda` | 本機連 ghcr 0.17 MB/s，2.6GB 要 3.5 小時 |
| Docker Hub 的 `nvidia/cuda` 當底、自己編 | Docker Hub 10 MB/s，編約 10 分鐘 |

## 決定

**llama.cpp 的 llama-server，模型 Qwen3-8B Q4_K_M，全部層放 GPU（`-ngl 99`）；映像檔兩段式自己 build，llama.cpp 釘版本、tarball 驗 SHA256。**

模型檔不直接 bind mount Windows 資料夾：實測只有約 20 MB/s，Qwen 載了 5 分鐘還卡在 275MB；
改由一次性的 `model-seed` 複製進 volume，從 volume 讀約 1.8 GB/s，載入回到 2.2 秒（[deploy/README.md](../../deploy/README.md)）。

## 代價

- 8B、不 thinking 的模型心算星期幾不可靠（「下週三」給過星期一、星期二），所以 prompt 附日期對照表、時間解析和空檔計算交給程式（[DateTable](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/shared/DateTable.java)、[CalendarAssistant](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/in/assistant/CalendarAssistant.java) 的「分工」註解）。
- 模型檔路徑寫死 `C:\llm\models`，只能在 Windows 上照原樣跑（README 的「已知限制」）。
- 雲端開不出 GPU（配額被拒），雲端跟本機不是同一顆模型（[0010](0010-aws-stage-one-ec2-compose-bedrock.md)）。
- 映像檔只編 `sm_89`；換到不同架構的 GPU（例如 T4 是 sm_75）要重編（[infra/envs/1-ec2/README.md](../../infra/envs/1-ec2/README.md)）。

## 什麼時候重想

- 要同時服務很多人 → 比較 vLLM 的吞吐量。
- GPU 配額下來 → 雲端用 `model_backend=gpu` 跑同一份映像檔，跟 Bedrock 比延遲和成本。

## 證據

- [deploy/compose.yaml](../../deploy/compose.yaml)：`llm-chat`、`model-seed` 和每個參數的理由
- [deploy/llama-server/Dockerfile](../../deploy/llama-server/Dockerfile)
- [deploy/README.md](../../deploy/README.md)：為什麼自己 build、為什麼不直接掛資料夾（實測數字）
