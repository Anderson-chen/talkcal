# 把本機 CD 登記成 Windows 工作排程，每幾分鐘在這個 clone 跑一輪 cd/triggers/watch.sh local。
# 在部署用的 clone 裡用 PowerShell 執行：
#
#   .\cd\triggers\install-local.ps1              # 每 5 分鐘
#   .\cd\triggers\install-local.ps1 -Minutes 2
#   Unregister-ScheduledTask talkcal-cd-local    # 拿掉
#
# 用排程、不寫常駐的迴圈：watch.sh 執行一輪就結束，記憶體裡沒有狀態，
# 電腦重開、Docker 還沒啟動、某一輪失敗，下一輪都會自己重來。
param([int]$Minutes = 5)
$ErrorActionPreference = 'Stop'

$clone = (Resolve-Path "$PSScriptRoot\..\..").Path

# 排程執行 .git 裡的副本，理由見 watch.sh 開頭。之後由 watch.sh 在部署成功後更新副本
$runner = "$clone\.git\cd-watch.sh"
Copy-Item "$PSScriptRoot\watch.sh" $runner -Force

# 要明確找 Git Bash：PATH 裡第一個 bash 可能是 WSL 的。
# git --exec-path 是 <Git>/mingw64/libexec/git-core，往上三層就是安裝目錄
$gitCore = (git --exec-path).Trim()
$bash = Join-Path (Split-Path (Split-Path (Split-Path $gitCore))) 'bin\bash.exe'
if (-not (Test-Path $bash)) { throw "找不到 Git Bash：$bash" }

# conhost --headless：不加的話每一輪都會跳出一個黑色視窗
$action = New-ScheduledTaskAction -Execute 'conhost.exe' `
  -Argument "--headless `"$bash`" `"$runner`" local" -WorkingDirectory $clone
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes $Minutes)
# IgnoreNew：上一輪還沒執行完就不開新的，跟 watch.sh 的鎖是兩道保險。
# 一輪最多一小時，卡住的會被終止；watch.sh 也把超過 60 分鐘的鎖當成殘留清掉
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Hours 1) -StartWhenAvailable

Register-ScheduledTask -TaskName 'talkcal-cd-local' -Action $action -Trigger $trigger -Settings $settings `
  -Description "talkcal 本機 CD：$clone" -Force | Out-Null
Write-Host "已登記 talkcal-cd-local：每 $Minutes 分鐘跑 $runner local"
Write-Host "log：$clone\.git\cd-local.log"
