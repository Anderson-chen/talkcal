# deploy/models —— 模型檔從哪來

模型檔（`.gguf`，幾 GB）不進 git、也不包進映像檔。每台電腦自己下載一份到 `MODELS_DIR`，
`deploy/compose.yaml` 的 `model-seed` 再從那裡複製進 Docker volume（為什麼要複製一次見 [deploy/README.md](../README.md#模型檔怎麼進容器)）。

## 要哪些檔案

| 檔名 | 大小 | 從哪來 | SHA256 |
|---|---|---|---|
| `Qwen3-8B-Q4_K_M.gguf` | 5,027,783,488 bytes（4.7 GiB） | Hugging Face [`Qwen/Qwen3-8B-GGUF`](https://huggingface.co/Qwen/Qwen3-8B-GGUF)，釘在 commit `7c41481f` | `d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785` |

清單的正本是 [`manifest.txt`](manifest.txt)，腳本讀的就是它；這張表只是給人看。

## 下載

```bash
cp deploy/.env.example deploy/.env      # 還沒建的話；填 MODELS_DIR（和 DB_PASSWORD）
bash deploy/models/download.sh          # 下載到 deploy/.env 的 MODELS_DIR
```

或直接給資料夾：`bash deploy/models/download.sh ~/llm/models`。

- **Windows** 在 Git Bash 裡跑（`ci/run.sh` 也是 Git Bash，裝 Git 就有）；**macOS、Linux** 用內建的 bash、curl
- 已經有、SHA256 對得上就跳過；下載中斷了重跑，會從斷掉的地方接著下
- SHA256 對不上：刪掉下載到的檔案、失敗結束，壞檔不會留在正式的檔名上
- 跑完會在資料夾裡寫一份 `SHA256SUMS`，`model-seed` 複製進 volume 時拿它再驗一次

已經有模型檔的資料夾（例如作者電腦上的 `C:/llm/models`）：不必重新下載，`MODELS_DIR` 指過去就好。
想確認那份是不是同一個檔案，對它跑一次 `download.sh` —— 只驗證、不下載。

## 不用腳本的話

```bash
curl -fL -o Qwen3-8B-Q4_K_M.gguf \
  https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/7c41481f57cb95916b40956ab2f0b139b296d974/Qwen3-8B-Q4_K_M.gguf
sha256sum Qwen3-8B-Q4_K_M.gguf          # macOS：shasum -a 256；PowerShell：Get-FileHash Qwen3-8B-Q4_K_M.gguf
```

算出來的要跟上表一樣。

## 為什麼網址釘 commit、不用 `resolve/main`

上游哪天重新量化、用同一個檔名換掉檔案，`main` 拿到的就不是這一份 —— 兩台電腦下載的時間不同，跑的模型就不同。
釘住 commit，再加上 SHA256，換幾台電腦拿到的都是同一個 byte。SHA256 是 Hugging Face 在這個 commit 記錄的
LFS 雜湊，也跟作者電腦上一直在用的那份一致。

換模型時三個地方一起改：`manifest.txt` 這一行、`deploy/compose.yaml` 裡 `llm-chat` 的 `-m`、`model-seed` 檢查的檔名。
