param(
    [switch]$Stop,
    [switch]$Quit,
    [switch]$Status
)

$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$Root = $PSScriptRoot
$Port = 9527
$Url = 'http://localhost:6866/boss'
$LogDir = Join-Path $Root 'target\logs'
$LauncherOut = Join-Path $LogDir 'launcher.out.log'
$LauncherErr = Join-Path $LogDir 'launcher.err.log'
$IconPath = Join-Path $Root 'front\app\favicon.ico'

# ==================== 本实例身份校验（P0-3） ====================
# netstat 只能说明“谁在监听端口”，不能说明它是不是本安装目录的 OfferChaser。
# 历史实现直接把监听 PID 当成本实例并 taskkill，可能误杀同机另一份副本或无关进程。
# 这里统一按进程信息判定归属：可执行文件路径以本目录开头，或命令行包含本目录。

function Get-CimProcessByPid {
    param([int]$ProcessId)
    return Get-CimInstance Win32_Process -Filter ("ProcessId=" + $ProcessId) -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Test-ProcessBelongsToRoot {
    param($ProcessInfo, [string]$InstallRoot)
    if (-not $ProcessInfo) { return $false }

    $rootPath = [string]$InstallRoot
    try { $rootPath = [System.IO.Path]::GetFullPath($rootPath) } catch { }
    $rootPath = $rootPath.TrimEnd('\')
    if (-not $rootPath) { return $false }

    $executablePath = [string]$ProcessInfo.ExecutablePath
    if ($executablePath) {
        try { $executablePath = [System.IO.Path]::GetFullPath($executablePath) } catch { }
        $rootPrefix = $rootPath + '\'
        if ($executablePath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
        if ($executablePath.Equals($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }

    $commandLine = [string]$ProcessInfo.CommandLine
    if ($commandLine -and $commandLine.IndexOf($rootPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }

    return $false
}

function Get-ListeningPid {
    param([int]$LocalPort)
    $lines = & netstat.exe -ano 2>$null | Select-String (':' + $LocalPort + '\s+') | Select-String 'LISTENING'
    foreach ($line in $lines) {
        $parts = ($line.ToString().Trim() -split '\s+')
        if ($parts.Length -ge 5) {
            $candidate = 0
            if ([int]::TryParse($parts[-1], [ref]$candidate)) {
                if ($candidate -gt 0) { return $candidate }
            }
        }
    }
    return $null
}

# 只返回“本实例且监听中”的 PID；端口被外部进程占用时返回 $null。
function Get-AppPid {
    $ownerPid = Get-ListeningPid -LocalPort $Port
    if (-not $ownerPid) { return $null }
    $ownerInfo = Get-CimProcessByPid -ProcessId $ownerPid
    if (Test-ProcessBelongsToRoot -ProcessInfo $ownerInfo -InstallRoot $Root) { return $ownerPid }
    return $null
}

# 端口被外部进程占用时返回该进程信息（用于提示），否则返回 $null。
function Get-ExternalPortOwner {
    $ownerPid = Get-ListeningPid -LocalPort $Port
    if (-not $ownerPid) { return $null }
    $ownerInfo = Get-CimProcessByPid -ProcessId $ownerPid
    if (-not $ownerInfo) { return $null }
    if (Test-ProcessBelongsToRoot -ProcessInfo $ownerInfo -InstallRoot $Root) { return $null }
    return $ownerInfo
}

function Format-ExternalOwner {
    param($Owner)
    if (-not $Owner) { return '' }
    $ownerName = [string]$Owner.Name
    $ownerPath = [string]$Owner.ExecutablePath
    if (-not $ownerPath) { $ownerPath = '(无法读取路径)' }
    return ("PID=" + $Owner.ProcessId + " 进程=" + $ownerName + " 路径=" + $ownerPath)
}

function Test-App {
    return [bool](Get-AppPid)
}

function Test-Backend {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Start-App {
    # 本实例已在监听：直接打开管理台，不重复启动。
    if (Get-AppPid) {
        Start-Process $Url
        return
    }

    # 端口被外部进程占用：明确拒绝并提示，绝不打开别人的页面（历史上任何监听都会被当成“已运行”）。
    $externalOwner = Get-ExternalPortOwner
    if ($externalOwner) {
        $ownerText = Format-ExternalOwner -Owner $externalOwner
        Add-Content -Path $LauncherOut -Value ("[start] 端口 " + $Port + " 被外部进程占用，拒绝启动 | " + $ownerText + " | Root=" + $Root) -Encoding UTF8
        [System.Windows.Forms.MessageBox]::Show(
            ("端口 " + $Port + " 已被外部进程占用，OfferChaser 无法启动（不会打开对方页面）：`n" + $ownerText + "`n`n请先关闭占用该端口的程序后重试。"),
            'OfferChaser', 'OK', 'Error') | Out-Null
        return
    }

    # 端口在监听但本实例 PID 暂时拿不到（极短竞态）：先等端口释放，避免叠加第二个 JVM。
    if (Test-Backend) {
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Seconds 1
            if (-not (Test-Backend)) { break }
        }
        if (Test-Backend) {
            Add-Content -Path $LauncherOut -Value ("[start] 端口 " + $Port + " 仍在监听但无法识别占用进程，拒绝启动 | Root=" + $Root) -Encoding UTF8
            [System.Windows.Forms.MessageBox]::Show(
                ("端口 " + $Port + " 仍在监听，但无法识别占用它的进程（可能权限不足），为避免重复启动/误连已拒绝启动。
请以管理员身份查看端口占用，或稍后重试。"),
                'OfferChaser', 'OK', 'Error') | Out-Null
            return
        }
    }

    $batch = Join-Path $Root '启动.bat'
    try {
        Start-Process -FilePath 'cmd.exe' `
            -ArgumentList @('/c', ('"' + $batch + '"')) `
            -WorkingDirectory $Root `
            -WindowStyle Hidden `
            -ErrorAction Stop | Out-Null
    } catch {
        Add-Content -Path $LauncherErr -Value ("启动失败: " + $_.Exception.Message) -Encoding UTF8
        [System.Windows.Forms.MessageBox]::Show(
            "OfferChaser 启动失败:`n" + $_.Exception.Message,
            'OfferChaser', 'OK', 'Error') | Out-Null
        return
    }

    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Backend) {
            # 应用自身 StartupRunner 会自动打开管理台；这里不重复开浏览器
            Start-Sleep -Seconds 2
            return
        }
    }

    [System.Windows.Forms.MessageBox]::Show(
        "OfferChaser 启动超时。请查看:`n$LogDir\offerchaser.log",
        'OfferChaser', 'OK', 'Warning') | Out-Null
}

function Open-Admin {
    if (Test-App) {
        Start-Process $Url
        return
    }
    $externalOwner = Get-ExternalPortOwner
    if ($externalOwner) {
        $ownerText = Format-ExternalOwner -Owner $externalOwner
        [System.Windows.Forms.MessageBox]::Show(
            ("端口 " + $Port + " 被外部进程占用，无法打开管理台：`n" + $ownerText),
            'OfferChaser', 'OK', 'Warning') | Out-Null
        return
    }
    Start-App
}

function Stop-BackendTask {
    # D-05：先让后端取消任务并等待 worker 退出；只有超时才由 Stop-App 兜底 kill。
    $tokenPath = Join-Path $Root 'data\local-session-token'
    if (-not (Test-Path -LiteralPath $tokenPath)) { return }
    try {
        $token = (Get-Content -LiteralPath $tokenPath -Raw).Trim()
        if (-not $token) { return }
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        Invoke-RestMethod -Uri 'http://127.0.0.1:9527/api/session/login' -Method POST `
            -ContentType 'application/json' -Body (@{ token = $token } | ConvertTo-Json) `
            -WebSession $session -TimeoutSec 3 | Out-Null

        $status = Invoke-RestMethod -Uri 'http://127.0.0.1:9527/api/boss/status' `
            -WebSession $session -TimeoutSec 3
        $state = [string]$status.state
        $runningStates = @('RUNNING', 'STARTING', 'PAUSED_VERIFICATION', 'STOPPING')
        if ($runningStates -notcontains $state) { return }

        Add-Content -Path $LauncherOut -Value ("[stop] 请求后端优雅停止 | state=" + $state) -Encoding UTF8
        try {
            Invoke-RestMethod -Uri 'http://127.0.0.1:9527/api/boss/stop' -Method POST `
                -WebSession $session -Headers @{ 'X-Requested-With' = 'offerchaser' } -TimeoutSec 5 | Out-Null
        } catch {
            Add-Content -Path $LauncherOut -Value ("[stop] 停止请求未成功，继续等待现有 worker：" + $_.Exception.Message) -Encoding UTF8
        }

        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 1
            try {
                $current = Invoke-RestMethod -Uri 'http://127.0.0.1:9527/api/boss/status' `
                    -WebSession $session -TimeoutSec 3
                $currentState = [string]$current.state
                if (($runningStates -notcontains $currentState) -and ($current.workerExited -eq $true)) {
                    Add-Content -Path $LauncherOut -Value ("[stop] worker 已退出 | state=" + $currentState) -Encoding UTF8
                    return
                }
            } catch {
                # 后端已关闭 / 端口消失时直接结束等待
                return
            }
        }
        Add-Content -Path $LauncherOut -Value '[stop] 等待 worker 退出超时，执行兜底结束进程' -Encoding UTF8
    } catch {
        Add-Content -Path $LauncherOut -Value ("[stop] 优雅停止不可用：" + $_.Exception.Message) -Encoding UTF8
    }
}

function Clear-InstanceLaunchers {
    # 只清理本安装目录启动的隐藏启动批处理；绝不使用全盘通配，避免误杀其它副本。
    $launcherPattern = '*' + $Root + '\启动.bat*'
    Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and ($_.CommandLine -like $launcherPattern) } |
        ForEach-Object { & taskkill.exe /PID $_.ProcessId /T /F | Out-Null }
}

function Stop-App {
    $appPid = Get-AppPid
    if ($appPid) {
        # 先取消当前任务并等 worker 退出（最多 60s）；随后再结束进程树。
        Stop-BackendTask
        # 进程本身仍需结束（Spring Boot 未暴露独立 shutdown 动作）；
        # 关键保证是：任务 worker 已先退出，不再产生 RUNNING/UNKNOWN 残留。
        & taskkill.exe /PID $appPid /T /F | Out-Null
        Start-Sleep -Milliseconds 800
        # 清理本实例遗留的隐藏启动批处理（应用被停止后，cmd 可能停在 pause 上）
        Clear-InstanceLaunchers
        return
    }

    # 端口被外部进程占用：拒绝停止，避免误杀同机另一份副本或无关进程（P0-3）。
    $externalOwner = Get-ExternalPortOwner
    if ($externalOwner) {
        $ownerText = Format-ExternalOwner -Owner $externalOwner
        Add-Content -Path $LauncherOut -Value ("[stop] 端口 " + $Port + " 被外部进程占用，拒绝停止（非本目录实例） | " + $ownerText + " | Root=" + $Root) -Encoding UTF8
        [System.Windows.Forms.MessageBox]::Show(
            ("端口 " + $Port + " 被另一个进程占用，它不属于本目录（" + $Root + "），已拒绝停止以免误杀：`n" + $ownerText + "`n`n如确认要停止它，请到它所属的安装目录操作。"),
            'OfferChaser', 'OK', 'Warning') | Out-Null
        return
    }

    # 本实例未运行、端口也空闲：只清理本目录启动器，不触碰其它副本。
    Clear-InstanceLaunchers
}

if ($Status) {
    $appPid = Get-AppPid
    if ($appPid) {
        'RUNNING ' + $appPid
    } else {
        $externalOwner = Get-ExternalPortOwner
        if ($externalOwner) { 'PORT_OCCUPIED ' + (Format-ExternalOwner -Owner $externalOwner) } else { 'STOPPED' }
    }
    exit 0
}

if ($Stop) {
    Stop-App
    if ($Quit) {
        # 停止服务时同时退出本目录的托盘，双击「停止」即完全退出；下次双击 OfferChaser.vbs 会重新拉起
        # 只结束本目录启动的托盘进程，避免误杀其它安装目录的托盘（P0-3）
        $trayPattern = '*' + $Root + '\OfferChaser.ps1*'
        Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -and ($_.CommandLine -like $trayPattern) } |
            ForEach-Object { & taskkill.exe /PID $_.ProcessId /T /F | Out-Null }
    }
    exit 0
}

# 防止重复双击生成多个托盘图标；已有托盘时，快捷方式只负责启动服务/打开管理台
$createdNew = $false
$trayMutex = [System.Threading.Mutex]::new($true, 'Local\OfferChaserTray', [ref]$createdNew)
if (-not $createdNew) {
    Start-App
    $trayMutex.Dispose()
    exit 0
}

[System.Windows.Forms.Application]::EnableVisualStyles()

$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = New-Object System.Drawing.Icon($IconPath)
$notify.Text = 'OfferChaser'
$notify.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$openItem = $menu.Items.Add('打开管理台')
$startItem = $menu.Items.Add('启动服务')
$stopItem = $menu.Items.Add('停止服务')
$menu.Items.Add('-') | Out-Null
$exitItem = $menu.Items.Add('退出')

$openItem.add_Click({ Open-Admin })
$startItem.add_Click({ Start-App })
$stopItem.add_Click({
    if ([System.Windows.Forms.MessageBox]::Show(
            '确定停止 OfferChaser？已发送的消息不会撤回。',
            'OfferChaser', 'YesNo', 'Warning') -eq 'Yes') {
        Stop-App
    }
})
$exitItem.add_Click({
    if (Test-App) {
        if ([System.Windows.Forms.MessageBox]::Show(
                '退出将停止 OfferChaser 后台服务，确定？',
                'OfferChaser', 'YesNo', 'Warning') -ne 'Yes') {
            return
        }
        Stop-App
    }
    $notify.Visible = $false
    $notify.Dispose()
    [System.Windows.Forms.Application]::Exit()
})

$notify.ContextMenuStrip = $menu
$notify.add_MouseDoubleClick({ Open-Admin })
$notify.add_BalloonTipClicked({ Open-Admin })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 3000
$timer.add_Tick({
    if (Test-Backend) {
        $notify.Text = 'OfferChaser · 运行中'
        $startItem.Enabled = $false
        $stopItem.Enabled = $true
    } else {
        $notify.Text = 'OfferChaser · 未运行'
        $startItem.Enabled = $true
        $stopItem.Enabled = $false
    }
})
$timer.Start()

Start-App

$notify.ShowBalloonTip(3000, 'OfferChaser', '服务已在后台启动，双击托盘图标打开管理台。', [System.Windows.Forms.ToolTipIcon]::Info)
[System.Windows.Forms.Application]::Run()

$timer.Stop()
$notify.Visible = $false
$notify.Dispose()
$trayMutex.ReleaseMutex()
$trayMutex.Dispose()
