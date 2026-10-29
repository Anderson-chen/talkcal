# 備份與還原（PostgreSQL）

> 沒還原過的備份不算備份。這份是「照著做就能完成」的步驟；為什麼這樣設計寫在最後一節。
> 追蹤：[#45](https://github.com/Anderson-chen/talkcal/issues/45)（epic [#38](https://github.com/Anderson-chen/talkcal/issues/38) ④ Respond）。
> 最近一次演練：[2026-10-19](../drills/2026-10-19-restore-drill.md)，2 萬筆行程誤刪，`pg-restore.sh` **3 秒**換回來（含停、起假 app）。

## 一眼看懂

| | 本機（`deploy/` 的 compose） | 雲端（`infra/envs/1-ec2`） |
|---|---|---|
| 什麼時候備份 | 手動：`bash deploy/backup/pg-backup.sh` | 開機期間**每小時**、**關機前**、**部署換新版前**（自動） |
| 備份放哪 | `~/talkcal-backups/`（repo 外、Docker volume 外） | 機器上 `/opt/talkcal/backups/`（2 天）＋ **S3** `talkcal-backups-<帳號ID>/postgres/`（30 天） |
| 保留多久 | 14 天，但最新 7 份一定留 | S3 30 天（lifecycle 刪；被覆寫的舊版再留 7 天）；機器上 2 天、最新 3 份一定留 |
| 誰刪得掉 | 你 | 只有 S3 lifecycle。機器的 IAM 角色**沒有** `s3:DeleteObject` |
| **RPO**（最多掉多少資料） | 距離上次手動備份 | 正常關機：**0**；開機中當機或整台消失：**≤ 1 小時** |
| **RTO**（多久救回來） | **目標 5 分鐘**（還原實測 3 秒；其餘是人找備份、真的 app 停止與重啟約 1 分鐘） | **目標 15 分鐘**（開機 1–2 分 ＋ 連線、選備份、下載 ＋ 還原秒級 ＋ app 重啟） |
| 備份怎麼確認是好的 | 每次備份：讀得出目錄＋寫 SHA256；還原前：比 SHA256＋整份還原到暫存庫 | 同左（同一支腳本） |

腳本都在 `deploy/backup/`：

| 檔案 | 做什麼 |
|---|---|
| `pg-backup.sh` | `pg_dump -Fc` → 驗目錄 → 寫 `.sha256` → 清過期的。本機、EC2 共用 |
| `pg-restore.sh` | 驗雜湊 → 驗目錄 → 還原到暫存庫 → 停 app、兩次改名換上 |
| `pg-diff.sh` | 比兩個資料庫：每張表依主鍵分成新增、刪除、改過（情境 C）。只讀 |
| `drill.sh` | 在獨立的容器上演練一整輪（含反例），不碰正在用的資料庫 |
| `infra/envs/1-ec2/backup-to-s3.sh` | （EC2）呼叫 `pg-backup.sh`，再傳上 S3 |

---

## 情境 A：本機——誤刪了，要回到最近一份備份

**1. 找備份**（檔名是 UTC 時間，最後一個就是最新的）

```bash
ls -l ~/talkcal-backups/
```

**2. 先演練一次**（不動線上，確認這份還原得回來、筆數是你預期的）

```bash
bash deploy/backup/pg-restore.sh ~/talkcal-backups/talkcal-<時間>.dump --verify-only
```

看輸出的兩行：「備份裡的筆數」和「線上目前的筆數」。誤刪的那張表，備份裡應該比線上多。

**3. 真的還原**（會自己停 `talkcal-app`、換好之後再起來）

```bash
bash deploy/backup/pg-restore.sh ~/talkcal-backups/talkcal-<時間>.dump
```

**4. 確認**

```bash
curl -fsS http://localhost:18090/actuator/health      # app 起來了
docker exec talkcal-postgres psql -U talkcal -d talkcal -c "SELECT count(*) FROM calendar_event"
```

然後打開 http://localhost:18080 看看行程回來了。

**5. 收尾**：被換下來的庫叫 `talkcal_before_<時間>`，留著是為了撈「備份之後才寫進去」的資料（見情境 C）。確定不需要了再刪：

```bash
docker exec talkcal-postgres psql -U talkcal -d postgres -c "DROP DATABASE talkcal_before_<時間>"
```

## 情境 B：雲端——誤刪了、或資料被新版程式寫壞了

**1. 開機、連進去**（在本機的 `infra/envs/1-ec2`）

```bash
aws ec2 start-instances --instance-ids "$(terraform output -raw instance_id)" --region ap-northeast-1 --profile DEV
aws ssm start-session --target "$(terraform output -raw instance_id)" --region ap-northeast-1 --profile DEV
```

**2. 選一份備份**：機器上有最近 2 天的；更早的在 S3。

```bash
sudo ls -l /opt/talkcal/backups/
B=talkcal-backups-$(aws sts get-caller-identity --query Account --output text)
aws s3 ls "s3://$B/postgres/" | tail -20
```

- 「部署新版之後資料壞了」→ 選部署那一刻的（`journalctl -u talkcal-backup* ` 或 deploy 的輸出裡有 `pre-deploy：完成 → …` 那行）
- 「剛剛誤刪」→ 選誤刪之前最新的那份

**3. 從 S3 拿下來**（機器上已經有就跳過）。`.dump` 和 `.sha256` 兩個都要：

```bash
sudo aws s3 cp "s3://$B/postgres/talkcal-<時間>.dump"        /opt/talkcal/backups/
sudo aws s3 cp "s3://$B/postgres/talkcal-<時間>.dump.sha256" /opt/talkcal/backups/
```

**4. 演練、再還原**（跟本機一樣，同一支腳本）

```bash
cd /opt/talkcal/repo
sudo bash deploy/backup/pg-restore.sh /opt/talkcal/backups/talkcal-<時間>.dump --verify-only
sudo bash deploy/backup/pg-restore.sh /opt/talkcal/backups/talkcal-<時間>.dump
```

**5. 確認**：開 `terraform output -raw app_url`，看行程回來了；`sudo docker ps` 看 `talkcal-app` 是 healthy。

**6. 如果是新版程式寫壞的**：還原之前先把程式退回舊版，不然新版一起來又寫壞一次：

```bash
sudo GIT_REF=<上一個好的 commit 或 tag> bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

> `deploy.sh` 一開始會先做一份 pre-deploy 備份——那份是「壞掉的資料」，不要拿它還原。
> 注意：還原的備份如果比現在的程式**舊**，app 起來時 Flyway 會把缺的 migration 補上（正常）；
> 如果比現在的程式**新**（有程式不認得的 migration），Flyway 會拒絕啟動——先把程式升到那一版。

## 情境 C：備份之後到現在變了什麼？先看差異，再決定救哪些

**備份的時間點**是 `pg_dump` 開始的那一刻（檔名上的 UTC 時間）：pg_dump 在一個交易快照裡讀，備份的過程中有人寫進來也不會混進這份，
那些會進下一份。所以「備份那一刻 → 現在」之間寫進去、刪掉、改掉的，就是整份還原會丟掉的部分（RPO 內的資料）。
不知道這段時間發生了什麼之前，不要急著整份還原：先用 `pg-diff.sh` 看出差異。

**C-1. 看差異**（只讀，線上不動）

```bash
KEEP_SCRATCH=1 bash deploy/backup/pg-restore.sh ~/talkcal-backups/talkcal-<時間>.dump --verify-only   # 留下 talkcal_restore = 備份的內容
bash deploy/backup/pg-diff.sh talkcal_restore talkcal                                                # 備份（舊）→ 線上（新）
```

每張表依主鍵對齊，分成三類，每類印前 5 筆（`SAMPLE=20` 可以多印）：

| 輸出 | 意思 | 通常是 |
|---|---|---|
| `- …` 刪除 | 備份裡有、線上沒有 | 誤刪的 |
| `+ …` 新增 | 備份之後才寫進去的 | 要留的新資料 |
| `~ 舊 → 新` 改過 | 主鍵一樣、內容不一樣 | 被改壞的，或正常的修改 |

演練時的輸出（備份之後多寫 1 則對話、改了 1 個帳號名字，然後誤刪全部行程）：

```text
比較：talkcal（舊）→ talkcal_before_20261028_141058（新）
  account                      新增 0、刪除 0、改過 1
      ~ (5ba8…,user1,…,"使用者 1") → (5ba8…,user1,…,備份之後改的名字)
  account_identity             一樣
  calendar_assistant_message   新增 1、刪除 0、改過 0
      + (632a…,0,USER,備份之後才寫的,…)
  calendar_event               新增 0、刪除 20000、改過 0
3 張表有差異
```

結束碼 0 = 完全一樣、3 = 有差異，腳本裡可以拿來判斷。

**C-2. 依差異決定怎麼救**

- **只有「刪除」、沒有要留的新增和修改** → 整份還原就好（情境 A／B），什麼都不會丟。
- **有要留的新資料** → 不要整份還原，只把誤刪的搬回來，線上其他的不動：

  ```bash
  docker exec talkcal-postgres sh -c "pg_dump -U talkcal -d talkcal_restore --data-only --inserts --on-conflict-do-nothing -t calendar_event | psql -U talkcal -d talkcal -q -v ON_ERROR_STOP=1"
  bash deploy/backup/pg-diff.sh talkcal_restore talkcal calendar_event      # 再比一次：刪除應該變 0
  docker exec talkcal-postgres psql -U talkcal -d postgres -c "DROP DATABASE talkcal_restore"
  ```

  `--inserts --on-conflict-do-nothing` 是一列一個 `INSERT ... ON CONFLICT DO NOTHING`：線上已經有的（主鍵相同）跳過，只補回不見的。
  不能用預設的 `COPY`：一列主鍵重複，整張表的 `COPY` 都會失敗。
  它**不會**把「改過」的改回去（主鍵已經在，被跳過）；要改回去的，照 `pg-diff.sh` 印出來的舊值手動 `UPDATE`。
- **已經整份還原了，才想到備份之後的新資料** → 它們還在被換下來的 `talkcal_before_<時間>`，反過來比、反過來搬：

  ```bash
  docker exec talkcal-postgres psql -U talkcal -d postgres -c "ALTER DATABASE talkcal_before_<時間> ALLOW_CONNECTIONS true"
  bash deploy/backup/pg-diff.sh talkcal talkcal_before_<時間>              # 「新增」就是備份之後才寫的
  docker exec talkcal-postgres sh -c "pg_dump -U talkcal -d talkcal_before_<時間> --data-only --inserts --on-conflict-do-nothing -t calendar_assistant_message | psql -U talkcal -d talkcal -q -v ON_ERROR_STOP=1"
  ```

雲端一樣，指令前面加 `sudo`，備份檔的路徑換成 `/opt/talkcal/backups/`。

## 改壞了要退回：還原之後發現選錯備份

兩次改名的反向就好（`talkcal` 現在是還原出來的那份）。跟 `pg-restore.sh` 一樣，改名前要先擋住新連線、踢掉剩下的：
停了 app 還有 `talkcal-postgres-exporter` 一直連著 `talkcal`，不踢掉的話改名會失敗（`database is being accessed by other users`）。

```bash
docker stop talkcal-app
docker exec -i talkcal-postgres psql -U talkcal -d postgres -v ON_ERROR_STOP=1 <<'SQL'
ALTER DATABASE talkcal_before_<時間> ALLOW_CONNECTIONS true;
ALTER DATABASE talkcal ALLOW_CONNECTIONS false;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'talkcal' AND pid <> pg_backend_pid();
BEGIN;
ALTER DATABASE talkcal RENAME TO talkcal_wrong_restore;
ALTER DATABASE talkcal_before_<時間> RENAME TO talkcal;
COMMIT;
SQL
docker start talkcal-app
```

---

## 每月演練一次

```bash
bash deploy/backup/drill.sh
```

在獨立的 `talkcaldrill-postgres`（127.0.0.1:15432）上跑完整一輪，跑完自己刪掉容器，**不碰 `talkcal-postgres`**：

1. 跑 backend 全部的 Flyway migration、種 2 萬筆行程
2. 備份 → `--verify-only` 演練（`pg-diff.sh` 要說「完全一樣」）→ 備份之後多寫、改一筆 → 誤刪（`DELETE` 忘了 `WHERE`）→ 還原 → 比對整庫指紋
3. 情境 C：`pg-diff.sh` 要找出誤刪的、多寫的、改過的，沒動的表不能誤報；再從被換下來的庫撈回多寫的那筆
4. 反例：改一個 byte、截掉後半、沒有雜湊檔、拿錯檔案、資料庫沒在跑時備份 —— 每個都要被擋下，線上資料不能變
5. 退回：反向改名，回到還原之前

任何一步跟預期不同，腳本就以非 0 結束。也可以拿正式的備份檔演練：`pg-restore.sh <檔> --verify-only` 不動線上。

## 備份有沒有在跑（雲端）

```bash
systemctl list-timers talkcal-backup.timer        # 下次什麼時候跑
journalctl -u talkcal-backup.service -n 20        # 每小時那次的輸出
journalctl -u talkcal-backup-on-shutdown -b -1    # 上次關機前那次的輸出
cat /opt/talkcal/backups/last-success             # 最後一次成功的時間（UTC）
```

「太久沒備份就告警」要等告警的 contact point（#152）合併；`last-success` 就是給它判斷用的。

---

## 為什麼這樣設計

### 為什麼用 pg_dump，不用 WAL 歸檔或磁碟快照

| 做法 | RPO | 好處 | 為什麼現在不選 |
|---|---|---|---|
| **pg_dump（選這個）** | 備份間隔 | 一個檔案；跨版本、跨機器都還原得回去；可以只救一張表；本機和雲端同一支腳本 | —— |
| WAL 歸檔（pgBackRest / WAL-G） | 秒～分鐘級，可還原到任一時間點 | 正式環境的標準做法 | 要多養一套工具和它的設定、監控；talkcal 的資料只有幾百 KB、寫入很少，1 小時的 RPO 夠用 |
| EBS 快照（Data Lifecycle Manager） | 快照間隔 | 整台機器一起回來 | 還原單位是整顆磁碟：救一張表也得換掉整台；而且是 crash-consistent，不是資料庫層級驗過的備份。機器本身可以從程式碼重建（user_data + deploy.sh），需要保護的只有資料庫 |
| RDS 自動備份 | ~5 分鐘 | 免維運、PITR | 要多一台 RDS（最小也比現在整台 EC2 貴），而且機器平常關機的省錢策略不適用 |

資料量或寫入量變大、RPO 1 小時不夠時，下一步是 WAL-G 歸檔到同一個 bucket，或直接搬 RDS。

### 為什麼 RPO 是這些數字

- 雲端機器**平常關機**，資料只在開機的時候變。所以關鍵是**關機前一定要有一份**：systemd 在停 Docker 之前跑 `backup-to-s3.sh shutdown`。正常關機（`aws ec2 stop-instances`、主控台的 Stop）的 RPO 就是 0。
- 開機期間每小時一次：一份 dump 幾秒鐘，一小時一次的成本幾乎是零；更頻繁的話 1 小時以下要換 WAL 歸檔才有意義。
- **部署前**一定備份，而且備份失敗就不部署：新版的 Flyway migration 改壞資料是最可能「整批壞掉」的情況，要回得去部署前那一刻。
- 本機是模擬環境，資料是開發用的，不排程；要保護的時候手動跑一次（或用 Windows 工作排程器跑 `bash deploy/backup/pg-backup.sh`）。

### 為什麼 RTO 是這些數字

- 實測（[演練紀錄](../drills/2026-10-19-restore-drill.md)）：2 萬筆行程、5 千則對話，`pg-restore.sh` 從開始到換好 3 秒（驗雜湊、目錄、還原到暫存庫、改名）。還原時間跟資料量大致成正比；talkcal 的實際資料量比演練小得多。
- 演練用的是會立刻處理 SIGTERM 的假 app。第一次演練的假 app 不處理 SIGTERM，`docker stop` 白等 10 秒才 SIGKILL，整段變 13 秒——真的 app 也一樣：它會等手上的請求做完（最多 2 分鐘），再加上 Spring 啟動約 30 秒，所以本機抓 5 分鐘、裡面大部分是停、起 app 和人的時間。
- 雲端的 15 分鐘是「人」的時間為主：發現問題、開機（1–2 分）、SSM 連線、選備份、下載（幾百 KB，秒級）、還原、真的 app 重啟（Spring 啟動約 30 秒，`stop_grace_period` 最多 2 分半）。

### 為什麼還原要先還原到暫存庫

演練的反例 2 證明了：**只截掉後半段的備份檔，`pg_restore --list` 還是讀得出目錄**（目錄在檔頭）。只靠目錄和雜湊擋不到「備份當下就寫不完整、雜湊是對著壞檔算的」這種情況。
所以真的還原之前，一定先整份還原到 `talkcal_restore`（單一交易、一錯就撤回）——這一步失敗，線上一個 byte 都沒動。
換上時只做兩次改名（同一個交易），所以要嘛都換了、要嘛都沒換；被換下來的那份留著，可以退回、也可以撈新資料。

### 為什麼備份放 S3、機器刪不掉

- 備份跟資料庫放同一顆 EBS，磁碟壞了、機器被 terminate、`terraform destroy`，兩個一起沒。
- 機器的 IAM 角色只有 `PutObject` / `GetObject` / `ListBucket`。機器被入侵，攻擊者能寫進垃圾，但版本控制留著舊版；刪不掉任何一份。
- 保留期由 S3 lifecycle 刪：機器平常關機，靠機器自己清的話關機期間永遠不會清。
- `force_destroy = false`：`terraform destroy` 遇到有備份的 bucket 會停下來，不會順手把最後一份也刪掉。

### 為什麼保留規則要「最新 N 份一定留」

只看天數的話，機器關了一個月再開，第一次備份完就把之前的全刪了——那正是最可能要回頭找舊資料的時候。
（反例在演練紀錄裡：目錄裡全是超過保留期的舊檔，跑完還留著最新的 3 份。）
