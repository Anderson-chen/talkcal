# 2026-10-19 還原演練：誤刪 → 還原

> [#45](https://github.com/Anderson-chen/talkcal/issues/45)。步驟和設計理由在 [runbooks/backup-restore.md](../runbooks/backup-restore.md)。
> 環境：Windows 11 + Docker Desktop、Git Bash，`postgres:18.6-alpine`（跟 `deploy/compose.yaml` 同版）。
> 全部在另外起的 `talkcaldrill-*` 容器上跑，沒碰正在用的 `talkcal-postgres`。

## 結果

| 項目 | 數字 |
|---|---|
| 資料量 | 50 帳號、20,000 筆行程、5,000 則對話（backend 全部 11 個 Flyway migration 建的 schema） |
| 備份 | 1 秒，784 KB |
| 還原演練（`--verify-only`，線上不動） | 3 秒 |
| **誤刪 → 資料回來** | **4 秒**（`pg-restore.sh` 本身 3 秒，含停、起假 app） |
| 還原後資料 | 整庫指紋（每張表依主鍵排序後的 md5）跟備份當下一樣 |
| RPO 示範 | 備份之後才寫的 1 則對話：線上消失（RPO 內），被換下來的庫裡還在，用情境 C 的指令撈回來，沒有重複 |
| 退回 | runbook「選錯備份」的反向改名可行：回到還原前（誤刪後）的狀態 |

同一天跑了 4 次，前兩次發現並修掉兩個問題：

1. 第一次「還原」花 13 秒：假 app 不處理 SIGTERM，`docker stop` 白等 10 秒才 SIGKILL。改成會處理 SIGTERM 之後 3–4 秒。
   真的 app 一樣要等：它會把手上的請求做完（最多 2 分鐘）——所以 RTO 的大頭是停、起 app，不是還原本身。
2. 被換下來的庫名稱原本帶大寫的 `T…Z`，PostgreSQL 沒加引號的名稱會轉小寫，log 印的跟實際名稱對不上。改成只用數字和底線。

## 反例：壞掉的備份、錯的步驟會被擋下

| # | 造出來的問題 | 被哪一步擋下 | 線上資料 |
|---|---|---|---|
| 1 | 備份檔中間改一個 byte | 1/4 雜湊對不上 | 沒動 |
| 2 | 只剩前半截、雜湊是對著截斷的檔案算的 | **目錄讀得出來**（只看目錄擋不到）→ 3/4 還原到暫存庫失敗 | 沒動 |
| 3 | 沒有 `.sha256` | 1/4 拒絕還原 | 沒動 |
| 4 | 拿錯檔案（一個 SQL 文字檔） | 2/4 讀不出目錄 | 沒動 |
| 5 | 資料庫沒在跑時備份 | `pg-backup.sh` 非 0 結束 | 備份資料夾沒多出任何檔案（沒有半成品） |
| 6 | 機器關了一個多月，目錄裡全是超過保留期的舊檔 | 保留規則刪掉過期的，**最新 3 份照留** | — |
| 7 | 上傳 S3 失敗 | `backup-to-s3.sh` 非 0 結束 | `last-success` 沒更新（告警看得到） |
| 8 | systemd unit 寫錯 key（`OnCalendar=every-hour`） | `systemd-analyze verify` 報錯 | — |

反例 2 是「先還原到暫存庫、成功才換上」這個設計的理由。

## 演練輸出（`bash deploy/backup/drill.sh`，第 4 次）

```text
[   0s] ── 準備：起 talkcaldrill-postgres（15432）和假 app，跑 backend 的 Flyway migration
NOTICE:  table "conversation_message" does not exist, skipping
NOTICE:  table "conversation" does not exist, skipping
migration：V1__create_conversations.sql V2__create_calendar_events.sql V3__index_calendar_event_period.sql V4__calendar_event_title_not_blank.sql V5__calendar_event_category_location_note.sql V6__spring_ai_chat_memory.sql V7__create_calendar_assistant_message.sql V8__drop_unused_chat_tables.sql V9__create_account.sql V10__account_external_identity.sql V11__calendar_owner.sql

[   9s] ── 種資料：50 個帳號、每人 400 筆行程、100 則對話
50 accounts, 20000 events, 5000 messages
指紋：d24b2f6534bddc5a2353219204de6346

[  10s] ── 備份（pg-backup.sh）
[pg-backup] pg_dump talkcaldrill-postgres/talkcal → /tmp/drill45d/talkcal-20261019T021342Z.dump
[pg-backup] 完成：784681 bytes、4 張表的資料、1 秒
備份花了 1 秒：talkcal-20261019T021342Z.dump，784681 bytes
07ae520dad3bd3c7e988ceeaf3ae6cdcf082ffffbf1f9403295bc4b5c2c538e6 *talkcal-20261019T021342Z.dump

[  11s] ── 演練還原（--verify-only）：證明這份備份還原得回來，線上不動
[pg-restore] 1/4 雜湊：/tmp/drill45d/talkcal-20261019T021342Z.dump.sha256
[pg-restore] 2/4 目錄
[pg-restore] 3/4 還原到暫存庫 talkcal_restore
NOTICE:  database "talkcal_restore" does not exist, skipping
[pg-restore] 備份裡的筆數：account=50 account_identity=0 calendar_assistant_message=5000 calendar_event=20000 
[pg-restore] 線上目前的筆數：account=50 account_identity=0 calendar_assistant_message=5000 calendar_event=20000 
[pg-restore] 演練完成：這份備份還原得回來（3 秒）。線上沒有動

[  14s] ── 備份之後又寫進一則對話（還原會讓它從線上消失：這就是 RPO）

[  14s] ── 事故：誤刪 —— DELETE 忘了加 WHERE
刪掉 DELETE 20000
50 accounts, 0 events, 5001 messages

[  15s] ── 還原（pg-restore.sh，真的換掉線上）
[pg-restore] 1/4 雜湊：/tmp/drill45d/talkcal-20261019T021342Z.dump.sha256
[pg-restore] 2/4 目錄
[pg-restore] 3/4 還原到暫存庫 talkcal_restore
NOTICE:  database "talkcal_restore" does not exist, skipping
[pg-restore] 備份裡的筆數：account=50 account_identity=0 calendar_assistant_message=5000 calendar_event=20000 
[pg-restore] 線上目前的筆數：account=50 account_identity=0 calendar_assistant_message=5001 calendar_event=0 
[pg-restore] 4/4 換上：talkcal → talkcal_before_*，talkcal_restore → talkcal
[pg-restore] 停 talkcaldrill-app（它握著連線）
[pg-restore] 換上完成。被換下來的是 talkcal_before_20261019_021349（確認沒問題後再 DROP，指令見 runbook）
[pg-restore] 起 talkcaldrill-app
[pg-restore] 還原完成（3 秒）
還原指令花了 3 秒；從誤刪到資料回來共 4 秒

[  18s] ── 驗證：資料一筆不差地回來、假 app 又連上了、被換下來的庫還在
50 accounts, 20000 events, 5000 messages
指紋：d24b2f6534bddc5a2353219204de6346
✓ 指紋跟備份當下一樣
假 app 狀態：running，連線數：1
被換下來的庫：talkcal_before_20261019_021349
線上有沒有備份之後才寫的那則：0 則（RPO 內的資料，還原會失去）
被換下來的庫裡有沒有：1 則（還撈得回來）

[  24s] ── 情境 C（runbook）：從被換下來的庫只撈回備份之後才寫的那則，線上已有的不重複
50 accounts, 20000 events, 5001 messages
✓ 撈回來了，其他 5000 則沒有重複

[  25s] ── 反例 1：備份檔中間被改了一個 byte → 雜湊擋下
✓ 擋下來了：[pg-restore] 停止：雜湊對不上：備份檔在複製或傳輸途中壞了，換一份 

[  26s] ── 反例 2：備份檔只剩前半截（雜湊是截斷後才算的，所以雜湊會過）→ 暫存庫還原那步擋下
目錄讀得出來嗎：讀得出來（只看目錄擋不到）
✓ 擋下來了：pg_restore: error: could not read from input file: end of file [pg-restore] 停止：還原到暫存庫失敗（檔案不完整或內容有錯）。線上的 talkcal 沒有動 

[  28s] ── 反例 3：沒有雜湊檔 → 不還原
✓ 擋下來了：[pg-restore] 停止：沒有雜湊檔 /tmp/drill45d/nosum.dump.sha256：不知道這份備份是不是完整的，不還原 

[  28s] ── 反例 4：拿錯檔案（不是 pg_dump 的檔）→ 目錄那步擋下
✓ 擋下來了：pg_restore: error: input file does not appear to be a valid archive (too short?) [pg-restore] 停止：pg_restore 讀不出目錄：不是 pg_dump -Fc 的檔案，或檔頭壞了 

[  28s] ── 反例 5：資料庫沒在跑時備份 → 失敗，而且不留下半成品
✓ 擋下來了：Error response from daemon: container 2e5ea3824c65db67a7f12300390bd770cb8b207821036f102339e3311ef0dbc0 is not running 
✓ 備份資料夾沒有多出任何檔案

[  30s] ── 反例都跑完：線上的資料沒被任何一個壞備份動到
✓ 指紋還是 0a02b50f73434e8efe9bd38078d92018

[  31s] ── 退回（runbook「還原之後發現選錯備份」）：換回被換下來的那份
50 accounts, 0 events, 5001 messages
✓ 回到還原之前（誤刪後）的樣子：退回的步驟可行

[  33s] ── 全部通過，清掉演練容器
```

## 雲端那一半：`backup-to-s3.sh`（容器裡模擬 EC2，假的 aws）

沒有真的 apply、沒動 AWS：在 `docker:27-cli` 容器裡掛 repo 到 `/opt/talkcaldrill/repo`、寫一份 `/opt/talkcal/deploy.env`
（`PROJECT=talkcaldrill`），`aws` 換成把 `s3 cp` 複製到本機資料夾的假程式。bucket 名稱一律由帳號 ID 推出來（`deploy.env` 不放這個欄位）。

```text
== 1. 正常：備份並上傳
[backup-to-s3] hourly：開始
[pg-backup] pg_dump talkcaldrill-postgres/talkcal → /opt/talkcaldrill/backups/talkcal-20261019T020647Z.dump
[pg-backup] 完成：3053 bytes、1 張表的資料、0 秒
[backup-to-s3] hourly：完成 → s3://talkcaldrill-backups-123456789012/postgres/talkcal-20261019T020647Z.dump
exit=0
/s3/calls.log
/s3/talkcaldrill-backups-123456789012/postgres/talkcal-20261019T020647Z.dump
/s3/talkcaldrill-backups-123456789012/postgres/talkcal-20261019T020647Z.dump.sha256
2026-10-19T02:06:47Z
== 2. 反例：上傳失敗 → 結束碼非 0、last-success 不更新
[backup-to-s3] hourly：開始
[pg-backup] pg_dump talkcaldrill-postgres/talkcal → /opt/talkcaldrill/backups/talkcal-20261019T020648Z.dump
[pg-backup] 完成：3053 bytes、1 張表的資料、1 秒
upload failed: AccessDenied
exit=1
last-success 沒動：2026-10-19T02:06:47Z
== 3. 保留規則（KEEP_DAYS=2 KEEP_MIN=3）：造 6 份舊檔
talkcal-20261001T000000Z.dump
talkcal-20261002T000000Z.dump
talkcal-20261003T000000Z.dump
talkcal-20261004T000000Z.dump
talkcal-20261005T000000Z.dump
talkcal-20261006T000000Z.dump
talkcal-20261019T020647Z.dump
talkcal-20261019T020648Z.dump
[pg-backup] 完成：3053 bytes、1 張表的資料、0 秒
[pg-backup] 過期刪除：talkcal-20261006T000000Z.dump
[pg-backup] 過期刪除：talkcal-20261005T000000Z.dump
[pg-backup] 過期刪除：talkcal-20261004T000000Z.dump
[pg-backup] 過期刪除：talkcal-20261003T000000Z.dump
[pg-backup] 過期刪除：talkcal-20261002T000000Z.dump
[pg-backup] 過期刪除：talkcal-20261001T000000Z.dump
[backup-to-s3] hourly：完成 → s3://talkcaldrill-backups-123456789012/postgres/talkcal-20261019T020649Z.dump
留下的：
talkcal-20261019T020647Z.dump
talkcal-20261019T020648Z.dump
talkcal-20261019T020649Z.dump
```

另外一次（Git Bash）：目錄裡只有 5 份 2026-09 的舊檔（KEEP_DAYS=2、KEEP_MIN=3）：

```text
[pg-backup] 完成：834 bytes、0 張表的資料、1 秒
[pg-backup] 過期刪除：talkcal-20260903T000000Z.dump
[pg-backup] 過期刪除：talkcal-20260902T000000Z.dump
[pg-backup] 過期刪除：talkcal-20260901T000000Z.dump
留下的：
talkcal-20260904T000000Z.dump
talkcal-20260905T000000Z.dump
talkcal-20261019T020704Z.dump
```

## systemd unit（`deploy.sh` 第 6 步產生的三個檔）

在 `ubuntu:24.04` 裡把 `deploy.sh` 那段 heredoc 照樣跑出來，再 `systemd-analyze verify`：

```text
/etc/systemd/system/talkcal-backup-on-shutdown.service
/etc/systemd/system/talkcal-backup.service
/etc/systemd/system/talkcal-backup.timer
VERIFY-OK
# 反例：OnCalendar=hourly 改成 every-hour
/etc/systemd/system/talkcal-backup.timer:5: Failed to parse calendar specification, ignoring: every-hour
talkcal-backup.timer: Timer unit lacks value setting. Refusing.
Unit talkcal-backup.timer has a bad unit file setting.
```

## 沒驗到的

- **真的 EC2 上的關機備份**：容器裡不是 PID 1 的 systemd，跑不了「關機時 ExecStop 在 Docker 之前跑」。依據是 systemd 關機時以啟動順序的反向停止 unit（`After=docker.service`）。下次開機器時要驗：`sudo systemctl stop talkcal-backup-on-shutdown` 看 S3 多一份，再真的 stop instance、開回來看 `journalctl -u talkcal-backup-on-shutdown -b -1`。
- **真的 S3、IAM**：只跑了 `terraform validate`，沒有 apply。IAM 少給的權限要等真的上去才會報 AccessDenied。

## 2026-10-28 補充：備份之後到現在變了什麼（PR 意見）

PR 上問：「備份那個時間點之後到現在的差異資料，算哪一個情境？」原本 runbook 的情境 C 只教「撈回來」，沒教「先看出差了什麼」。
補了 `deploy/backup/pg-diff.sh`，情境 C 改成「先看差異、再決定救哪些」。演練多造了三種備份之後的變化：多寫 1 則對話、改 1 個帳號名字、
建一張沒有主鍵、有三列重複的表（像新的 migration），然後誤刪全部行程。

| 反例 | 結果 |
|---|---|
| 剛備份完，`--verify-only` 留下的暫存庫 vs 線上 | `完全一樣`、結束碼 0（不誤報） |
| 誤刪的 20000 筆、多寫的 1 則、改過的 1 個帳號 | 分別算成刪除 20000、新增 1、改過 1 |
| 備份之後才建的表，三列一模一樣 | 新增 3（沒被合併成 1；表只在新的庫裡也比得到） |
| 沒動的表 | `一樣` |

演練當場抓到 `pg-diff.sh` 一個 bug：舊的庫那張表是空的時，awk 的 `NR == FNR` 在讀新的那份時也成立，新增被算成刪除（改用檔名分辨兩邊）。

```text
[  32s] ── 情境 C（runbook）：備份之後變了什麼 —— pg-diff.sh 備份的內容（現在的 talkcal）→ 被換下來的庫
比較：talkcal（舊）→ talkcal_before_20261028_225708（新）
  account                      新增 0、刪除 0、改過 1
      ~ (5ba8660c-4549-ae4f-5c93-0f73a54815fb,user1,{bcrypt}x,"2026-10-28 22:56:51.649087+00","使用者 1") → (5ba8660c-4549-ae4f-5c93-0f73a54815fb,user1,{bcrypt}x,"2026-10-28 22:56:51.649087+00",備份之後改的名字)
  account_identity             一樣
  assistant_proposal           一樣
  calendar_assistant_message   新增 1、刪除 0、改過 0
      + (632a2406-bbcb-cd55-3eec-45ac14b40a0a,0,USER,備份之後才寫的,,,"2026-10-28 22:57:04.322487+00",5ba8660c-4549-ae4f-5c93-0f73a54815fb)
  calendar_event               新增 0、刪除 20000、改過 0
      - (8d37f129-43a2-98b8-9599-54c9fcab8e29,"行程 7358","2026-07-09 01:26:00","2026-07-09 02:26:00","2026-10-28 22:56:51.651826+00",HEALTH,,,76ccdce3-f27e-1aa4-ab4c
  drill_after_backup           新增 3、刪除 0、改過 0
      + (1)
4 張表有差異
✓ 結束碼 3（有差異）
✓ 找到誤刪的 20000 筆行程
✓ 找到備份之後多寫的 1 則對話
✓ 找到備份之後改過的 1 個帳號
✓ 找到備份之後才建的表、重複的 3 列沒被合併
✓ 沒動的表不誤報
```

### 退回步驟有別的連線連著時

master 合進來的 `postgres-exporter` 會一直連著 `talkcal`。原本 runbook 的退回步驟只停 app，改名會撞上它：

```text
== 反例：舊版 runbook（沒踢連線）
DETAIL:  There is 1 other session using the database.
== 新版 runbook（先 ALLOW_CONNECTIONS false、pg_terminate_backend）
改名成功
```

演練的退回那步也改成先開一條一直連著的連線（模擬 exporter），再照 runbook 的 SQL 做：

```text
[  49s] ── 退回（runbook「還原之後發現選錯備份」）：換回被換下來的那份
50 accounts, 0 events, 5001 messages
✓ 回到還原之前（誤刪後）的樣子：退回的步驟可行
```

### 三個備份時機同一秒觸發

每小時的 timer、部署前、關機前可能撞在一起。`backup-to-s3.sh` 加了 `flock`，`pg-backup.sh` 遇到同一秒已經有一份就等下一秒。
第一次只加了鎖：三份會排隊，但 hourly 和 shutdown 落在同一秒，同一個檔名，後一份蓋掉前一份（三份只剩兩個檔）。再加上「等下一秒」之後：

```text
== 三個時機同一秒觸發
hourly: [backup-to-s3] hourly：完成 → s3://talkcal-backups-123456789012/postgres/talkcal-20261028T225845Z.dump
pre-deploy: [backup-to-s3] pre-deploy：完成 → s3://talkcal-backups-123456789012/postgres/talkcal-20261028T225843Z.dump
shutdown: [backup-to-s3] shutdown：完成 → s3://talkcal-backups-123456789012/postgres/talkcal-20261028T225844Z.dump
last-success
talkcal-20261028T225843Z.dump
talkcal-20261028T225843Z.dump.sha256
talkcal-20261028T225844Z.dump
talkcal-20261028T225844Z.dump.sha256
talkcal-20261028T225845Z.dump
talkcal-20261028T225845Z.dump.sha256
talkcal-20261028T225843Z.dump: OK
talkcal-20261028T225844Z.dump: OK
talkcal-20261028T225845Z.dump: OK
```
