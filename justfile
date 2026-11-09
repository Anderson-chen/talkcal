# 本機常用腳本的入口（#238）：`just` 或 `just --list` 列出全部指令和說明，`just <指令> [參數]` 執行。
# 這裡只轉呼叫原本的腳本，做法、參數的意思寫在各腳本開頭；腳本路徑沒變，直接執行照樣可以。
#
# 需要：just（winget install Casey.Just）、Git Bash。just 在 Windows 上用 PATH 裡的 `sh` 跑每一行，
# 在 Git Bash 裡就找得到；要從 PowerShell、VS Code 的 task 跑，把 <Git 安裝目錄>\bin 加進使用者的 PATH。
# 不寫死 bash 的路徑：Git 不一定裝在 C:\Program Files，PATH 裡第一個 bash 也可能是 WSL 的（見 cd/triggers/install-local.ps1）。
#
# 只在 EC2 上跑、由 systemd 或 user_data 呼叫的（infra/envs/1-ec2/deploy.sh、backup-to-s3.sh、smoke.sh）不放進來。
#
# 退版、真的還原、EC2 關機加了 [confirm]，執行前先問（--yes 跳過）。只擋從 just 進來的；直接跑腳本不會問。
# 同一支腳本的安全模式（--list、--unpin、--verify-only、start）拆成獨立的指令，才不必每次都回答。

# 參數用 "$@" 原樣交給腳本：用 {{ }} 內插的話，帶空白、引號的參數會被拆開
set positional-arguments

# 收檔案路徑的指令加 [no-cd]，在使用者所在的目錄跑：just 預設會先切到 repo 根目錄，
# 在子目錄給的相對路徑就會指錯地方。這時腳本要用 repo 的完整路徑叫
repo := justfile_directory()

[private]
default:
    @"{{just_executable()}}" --list --unsorted

# ── CI：跟 GitHub Actions、pre-push hook 同一套檢查（ci/README.md）──

# 在乾淨的容器裡跑全部 CI 檢查（不給 commit 就測 HEAD）
[group('ci')]
ci *commit:
    ci/run.sh "$@"

# 檢查後端 Java 的格式；`just format apply` 直接排好
[group('ci')]
format *mode:
    ci/format.sh "$@"

# 檢查新增、改動的 migration 能不能新舊版本同時在線（不給就跟 origin/master 比）
[group('ci')]
migrations *base:
    ci/migrations.sh "$@"

# 檢查 Mimir 的錄製規則
[group('ci')]
rules:
    ci/rules.sh

# 掃 git 歷史裡的祕密（參數是 git log 的語法，不給就掃 HEAD 往回）
[group('ci')]
secrets *log_opts:
    ci/secrets.sh "$@"

# 掃映像檔的已知漏洞，例如 `just vulns talkcal/app:local talkcal/web:local`
[group('ci')]
vulns +images:
    ci/vulns.sh "$@"

# 告警端到端測試，約 15 分鐘（KEEP=1 just alerting 跑完留著容器）
[group('ci')]
alerting:
    ci/alerting.sh

# ── CD：部署、發版、退版（cd/README.md、docs/release.md、docs/rollback.md）──

# 把這台機器的服務換成目前 checkout 的版本，不先跑測試（要先跑測試用 ./gradlew deployApp）；不給服務就部署 app、web
[group('cd')]
deploy env="local" *services:
    cd/apply.sh "$@"

# 印出目前 checkout 的版本號
[group('cd')]
version:
    cd/version.sh

# 在本機打 release tag，不會推上去（例如 `just release v0.2.0`）
[group('cd')]
release tag *commit:
    cd/release.sh "$@"

# 列出這台機器退得回去的版本、現在跑哪個、有沒有釘住
[group('cd')]
versions env="local":
    cd/rollback.sh "$1" --list

# 退到指定版本並釘住（版本號或 --previous）
[confirm("要把服務退版並釘住嗎？(y/N)")]
[group('cd')]
rollback env version:
    cd/rollback.sh "$@"

# 解除釘住，watcher 下一輪照常部署最新的綠燈版本
[group('cd')]
unpin env="local":
    cd/rollback.sh "$1" --unpin

# 手動跑一輪 CD watcher：master 有新的綠燈版本就部署
[group('cd')]
watch env="local":
    cd/triggers/watch.sh "$1"

# ── 備份、還原（docs/runbooks/backup-restore.md）──

# 備份 PostgreSQL 到 ~/talkcal-backups（BACKUP_DIR 可改）
[group('backup')]
backup:
    deploy/backup/pg-backup.sh

# 只演練還原：還原到暫存庫、印筆數、刪掉暫存庫，不碰線上的資料庫
[group('backup')]
[no-cd]
restore-verify file:
    "{{repo}}/deploy/backup/pg-restore.sh" "$1" --verify-only

# 真的還原：用備份檔換掉線上的資料庫
[confirm("要用備份換掉線上的資料庫嗎？(y/N)")]
[group('backup')]
[no-cd]
restore file:
    "{{repo}}/deploy/backup/pg-restore.sh" "$1"

# 比兩個資料庫差在哪（只讀），例如 `just pg-diff talkcal_restore talkcal`
[group('backup')]
pg-diff old new *tables:
    deploy/backup/pg-diff.sh "$@"

# 還原演練：在獨立的容器上跑備份 → 誤刪 → 還原，跑完全部刪掉
[group('backup')]
drill:
    deploy/backup/drill.sh

# ── EC2（infra/envs/1-ec2/README.md）──

# 開機，等到 Healthchecks 恢復監控
[group('ec2')]
ec2-start:
    infra/envs/1-ec2/power.sh start

# 暫停 Healthchecks 再關機
[confirm("要把 EC2 關機嗎？(y/N)")]
[group('ec2')]
ec2-stop:
    infra/envs/1-ec2/power.sh stop

# ── 壓測（perf/README.md）──

# 跑 k6 並把指標送進 ops/ 的 Grafana，例如 `just perf perf/knee.js`（後面的參數交給 k6 run）
[group('perf')]
[no-cd]
perf script *k6_args:
    "{{repo}}/perf/run-to-grafana.sh" "$@"
