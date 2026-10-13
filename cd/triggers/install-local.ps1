# 把本機 CD 登記成 Windows 工作排程：每幾分鐘在「這個 clone」跑一輪 cd/triggers/watch.sh local。
# 在部署用的 clone 裡跑（不是開發用的那份），PowerShell：
#
#   .\cd\triggers\install-local.ps1              # 每 5 分鐘
#   .\cd\triggers\install-local.ps1 -Minutes 2
#   Unregister-ScheduledTask talkcal-cd-local    # 拿掉
#
# 為什麼用工作排程而不是常駐的迴圈：watch.sh 一輪就結束，沒有狀態留在記憶體裡，
# 電腦重開、Docker 還沒起來、某一輪失敗，下一輪都會自己重來，不用另外顧一個常駐程式。
param([int]$Minutes = 5)
$ErrorActionPreference = 'Stop'

$clone = (Resolve-Path "$PSScriptRoot\..\..").Path

# 排程跑 .git 裡的副本，不跑 cd\triggers\watch.sh：後者會跟著 checkout 變，
# 部署到還沒有 watcher 的舊版本時就不見了（理由見 watch.sh 開頭）。之後由 watch.sh 在部署成功後自己更新副本
$runner = "$clone\.git\cd-watch.sh"
Copy-Item "$PSScriptRoot\watch.sh" $runner -Force

# 跟 backend/build.gradle 同一個理由：PATH 裡第一個 bash 是 WSL，要明確找 Git Bash。
# git --exec-path 是 <Git>/mingw64/libexec/git-core，往上三層就是安裝目錄
$gitCore = (git --exec-path).Trim()
$bash = Join-Path (Split-Path (Split-Path (Split-Path $gitCore))) 'bin\bash.exe'
if (-not (Test-Path $bash)) { throw "找不到 Git Bash：$bash" }

# conhost --headless：不帶的話每一輪都會跳出一個黑色視窗
$action = New-ScheduledTaskAction -Execute 'conhost.exe' `
  -Argument "--headless `"$bash`" `"$runner`" local" -WorkingDirectory $clone
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes $Minutes)
# IgnoreNew：上一輪還沒跑完（正在 build 映像檔）就不開新的，跟 watch.sh 裡的鎖是兩道保險。
# 一小時的上限：卡住的一輪會被砍掉，watch.sh 的鎖超過 60 分鐘也會被當成殘留清掉
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Hours 1) -StartWhenAvailable

Register-ScheduledTask -TaskName 'talkcal-cd-local' -Action $action -Trigger $trigger -Settings $settings `
  -Description "talkcal 本機 CD：$clone" -Force | Out-Null
Write-Host "已登記 talkcal-cd-local：每 $Minutes 分鐘跑 $runner local"
Write-Host "log：$clone\.git\cd-local.log"
