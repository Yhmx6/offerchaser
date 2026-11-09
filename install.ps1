# 一键安装.ps1 -- OfferChaser 一键安装器
#
# 用法（普通用户）：
#   双击同一目录下的「一键安装.bat」。
#
# 高级用法（排障 / 自动化）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\一键安装.ps1 -Auto
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\一键安装.ps1 -Dir D:\OfferChaser
#
# 参数：
#   -Dir <路径>     指定安装目录（默认 %USERPROFILE%\OfferChaser）
#   -Auto           无人值守：不询问，使用默认/指定目录
#   -NoLaunch       安装完成后不启动程序
#   -NoShortcut     不创建桌面和开始菜单快捷方式
#   -NoPause        出错时不等待按键（自动化用）
#
# 安装器只做四件事：检查环境 -> 复制程序文件 -> 建快捷方式 -> 启动。
# 不会删除安装目录里已有的 db\ data\ browser-data\（升级时保留你的数据）。

[CmdletBinding()]
param(
    [string]$Dir = '',
    [switch]$Auto,
    [switch]$NoLaunch,
    [switch]$NoShortcut,
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$Root = $PSScriptRoot
if (-not $Root) { $Root = (Get-Location).Path }
$Root = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')

function Write-Title($text) {
    Write-Host ''
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
    Write-Host ('  ' + $text) -ForegroundColor Cyan
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
}

function Write-Step($n, $text) {
    Write-Host ''
    Write-Host ('[' + $n + '] ' + $text) -ForegroundColor Yellow
}

function Write-Ok($text) { Write-Host ('    OK  ' + $text) -ForegroundColor Green }
function Write-Warn2($text) { Write-Host ('    !   ' + $text) -ForegroundColor Yellow }
function Write-Fail($text) { Write-Host ('    X   ' + $text) -ForegroundColor Red }

function Wait-Key {
    if ($NoPause) { return }
    Write-Host ''
    Write-Host '按回车键退出...' -ForegroundColor DarkGray
    try { [void](Read-Host) } catch { }
}

function Test-PortListening {
    param([int]$Port)
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

function Get-PortOwner {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
            Select-Object -First 1
        if (-not $conn) { return '' }
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            $path = ''
            try { $path = [string]$proc.Path } catch { $path = '(无法读取路径)' }
            return ('PID=' + $conn.OwningProcess + ' 进程=' + $proc.ProcessName + ' 路径=' + $path)
        }
        return ('PID=' + $conn.OwningProcess)
    } catch {
        return ''
    }
}

function Get-DesktopPath {
    $desktop = [Environment]::GetFolderPath('Desktop')
    if ($desktop -and (Test-Path -LiteralPath $desktop)) { return $desktop }
    return (Join-Path $env:USERPROFILE 'Desktop')
}

function Get-StartMenuProgramsPath {
    $programs = [Environment]::GetFolderPath('Programs')
    if ($programs -and (Test-Path -LiteralPath $programs)) { return $programs }
    return (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs')
}

function New-Shortcut {
    param(
        [string]$LinkPath,
        [string]$TargetPath,
        [string]$WorkingDirectory,
        [string]$IconPath,
        [string]$Description
    )
    try {
        $shell = New-Object -ComObject WScript.Shell
        $link = $shell.CreateShortcut($LinkPath)
        $link.TargetPath = $TargetPath
        $link.WorkingDirectory = $WorkingDirectory
        if ($IconPath -and (Test-Path -LiteralPath $IconPath)) { $link.IconLocation = $IconPath + ',0' }
        if ($Description) { $link.Description = $Description }
        $link.Save()
        return $true
    } catch {
        Write-Warn2 ('创建快捷方式失败：' + $LinkPath + '（' + $_.Exception.Message + '）')
        return $false
    }
}

function Test-ChromeInstalled {
    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe'
    )
    foreach ($key in $keys) {
        try {
            $value = (Get-ItemProperty -Path $key -Name '(default)' -ErrorAction Stop).'(default)'
            if ($value -and (Test-Path -LiteralPath $value)) { return $true }
        } catch { }
    }
    $paths = @(
        (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe')
    )
    foreach ($p in $paths) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $true }
    }
    return $false
}

function Get-DirectorySizeMB {
    param([string]$Path)
    try {
        $sum = (Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
        if (-not $sum) { return 0 }
        return [math]::Round($sum / 1MB)
    } catch {
        return 0
    }
}

function Assert-Package {
    Write-Step '1/6' '检查一键包文件'
    $required = @(
        'OfferChaser.jar',
        'runtime\bin\java.exe',
        'patchright-driver\package\cli.js',
        'src\main\resources\dist',
        'front\app\favicon.ico',
        'OfferChaser.vbs',
        '停止OfferChaser.vbs',
        '启动.bat',
        'OfferChaser.ps1'
    )
    $missing = @()
    foreach ($rel in $required) {
        $p = Join-Path $Root $rel
        if (-not (Test-Path -LiteralPath $p)) { $missing += $rel }
    }
    if ($missing.Count -gt 0) {
        throw ('一键包文件不完整，缺少：' + ($missing -join '、') + "`n请重新从 GitHub Releases 下载完整 zip 并先解压，不要直接在压缩包里运行。")
    }
    Write-Ok '一键包文件完整'

    # 如果包里有 release.json，校验 jar 的 SHA256，防止下载损坏或被改动。
    $manifestPath = Join-Path $Root 'release.json'
    if (Test-Path -LiteralPath $manifestPath) {
        try {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($manifest.jarSha256) {
                Write-Host '    正在校验程序文件完整性...' -ForegroundColor DarkGray
                $actual = (Get-FileHash -LiteralPath (Join-Path $Root 'OfferChaser.jar') -Algorithm SHA256).Hash
                if ($actual.ToUpperInvariant() -ne ([string]$manifest.jarSha256).ToUpperInvariant()) {
                    throw 'OfferChaser.jar 校验值不一致，安装包可能下载不完整，请重新下载 zip。'
                }
                Write-Ok 'OfferChaser.jar 校验通过'
            }
        } catch {
            if ($_.Exception.Message -like '*校验值不一致*') { throw }
            Write-Warn2 ('release.json 读取失败，跳过完整性校验：' + $_.Exception.Message)
        }
    }
}

function Assert-Environment {
    Write-Step '2/6' '检查系统环境'

    if (-not [Environment]::Is64BitOperatingSystem) {
        throw 'OfferChaser 只支持 64 位 Windows，当前系统不是 64 位。'
    }
    Write-Ok 'Windows 64 位'

    $osVersion = [Environment]::OSVersion.Version
    if ($osVersion.Major -lt 10) {
        throw ('需要 Windows 10 或 Windows 11，当前版本：' + $osVersion.ToString())
    }
    Write-Ok ('Windows ' + $osVersion.Major + '.' + $osVersion.Minor)

    if (Test-ChromeInstalled) {
        Write-Ok '已安装 Google Chrome'
    } else {
        Write-Warn2 '没有检测到 Google Chrome。OfferChaser 的浏览器自动化需要 Chrome，请先安装：https://www.google.com/chrome/'
    }

    foreach ($port in @(9527, 6866)) {
        if (Test-PortListening -Port $port) {
            $owner = Get-PortOwner -Port $port
            Write-Warn2 ('端口 ' + $port + ' 已被占用：' + $owner + '。程序可能无法启动，请先关闭占用该端口的程序。')
        } else {
            Write-Ok ('端口 ' + $port + ' 空闲')
        }
    }

    $tempDir = Join-Path $env:TEMP ('OfferChaser-install-' + [Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
        Remove-Item -LiteralPath $tempDir -Recurse -Force
        Write-Ok '临时目录可写'
    } catch {
        Write-Warn2 '临时目录写入失败，安装过程可能受影响：' + $_.Exception.Message
    }
}

function Resolve-InstallDir {
    $defaultDir = Join-Path $env:USERPROFILE 'OfferChaser'
    if ($Dir -and $Dir.Trim()) {
        $candidate = $Dir.Trim()
    } elseif ($Auto) {
        $candidate = $defaultDir
    } else {
        Write-Host ''
        Write-Host '    选择安装位置（直接回车使用默认）：' -ForegroundColor White
        Write-Host ('      ' + $defaultDir) -ForegroundColor DarkGray
        Write-Host '    也可以输入其他目录，例如 D:\OfferChaser' -ForegroundColor DarkGray
        $answer = ''
        try { $answer = Read-Host '    安装位置' } catch { $answer = '' }
        if ($answer -and $answer.Trim()) { $candidate = $answer.Trim().Trim('"') } else { $candidate = $defaultDir }
    }

    if (-not [System.IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $env:USERPROFILE $candidate
    }
    $full = [System.IO.Path]::GetFullPath($candidate).TrimEnd('\')

    if ($full.Length -le 3) {
        throw '安装目录不能是磁盘根目录，请换一个目录，例如 D:\OfferChaser。'
    }
    if ($full -match '[<>|"?*]') {
        throw '安装目录包含 Windows 不允许的字符，请换一个目录。'
    }
    return $full
}

function Test-TargetIsSelf {
    param([string]$Target)
    try {
        $a = [System.IO.Path]::GetFullPath($Target).TrimEnd('\')
        $b = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
        return ($a.Equals($b, [System.StringComparison]::OrdinalIgnoreCase))
    } catch {
        return $false
    }
}

function Resolve-TargetDir {
    Write-Step '3/6' '确定安装目录'
    $target = Resolve-InstallDir

    if (Test-TargetIsSelf -Target $target) {
        Write-Ok ('就地安装：' + $target)
        return $target
    }

    $isExistingApp = Test-Path -LiteralPath (Join-Path $target 'OfferChaser.jar')
    if ((Test-Path -LiteralPath $target) -and -not $isExistingApp) {
        $items = @(Get-ChildItem -LiteralPath $target -Force -ErrorAction SilentlyContinue)
        if ($items.Count -gt 0) {
            if ($Auto) {
                throw ('安装目录已存在且非空：' + $target + '。请换一个空目录，或删除该目录后重试。')
            }
            Write-Warn2 ('目录已存在且非空：' + $target)
            $ok = Read-Host '    继续会覆盖同名文件，但不会删除其它内容。继续？(y/N)'
            if ($ok -notmatch '^(y|Y)') { throw '已取消安装。' }
        }
    }
    if ($isExistingApp) {
        Write-Ok ('检测到已安装的 OfferChaser，将升级：' + $target + '（保留 db\ data\ browser-data\）')
    } else {
        Write-Ok ('安装目录：' + $target)
    }
    return $target
}

function Stop-RunningInstance {
    param([string]$Target)
    if (-not (Test-PortListening -Port 9527)) { return }
    $owner = Get-PortOwner -Port 9527
    $isOurs = $false
    if ($owner -and ($owner -like ('*' + $Target + '*') -or $owner -like ('*' + $Root + '*'))) {
        $isOurs = $true
    }
    if (-not $isOurs) {
        Write-Warn2 ('端口 9527 被其它程序占用，未自动处理：' + $owner)
        return
    }
    Write-Warn2 '检测到 OfferChaser 正在运行，先停止它再复制文件...'
    $ps1 = Join-Path $Root 'OfferChaser.ps1'
    if (Test-Path -LiteralPath $ps1) {
        try {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File $ps1 -Stop -Quit | Out-Null
        } catch { }
    }
    for ($i = 0; $i -lt 60; $i++) {
        if (-not (Test-PortListening -Port 9527)) { return }
        Start-Sleep -Seconds 1
    }
    Write-Warn2 'OfferChaser 仍在运行；如果复制失败，请先双击「停止OfferChaser.vbs」再重新安装。'
}

function Copy-Package {
    param([string]$Target)
    Write-Step '4/6' '复制程序文件'
    if (Test-TargetIsSelf -Target $Target) {
        Write-Ok '当前目录就是安装目录，跳过复制'
        return
    }

    Stop-RunningInstance -Target $Target

    $drive = [System.IO.Path]::GetPathRoot($Target)
    try {
        $driveInfo = New-Object System.IO.DriveInfo $drive
        $freeGB = [math]::Round($driveInfo.AvailableFreeSpace / 1GB, 1)
        $needMB = Get-DirectorySizeMB -Path $Root
        if ($freeGB -lt 1.5) {
            throw ('目标磁盘剩余空间不足（' + $freeGB + ' GB），安装大约需要 0.5 GB，运行后数据还会增长，请清理磁盘后重试。')
        }
        Write-Ok ('目标磁盘剩余 ' + $freeGB + ' GB（安装约 ' + $needMB + ' MB）')
    } catch {
        if ($_.Exception.Message -like '*剩余空间不足*') { throw }
    }

    New-Item -ItemType Directory -Path $Target -Force | Out-Null
    Write-Host '    正在复制，请稍候（根据磁盘速度约 10-90 秒）...' -ForegroundColor DarkGray
    $rc = 0
    $null = robocopy $Root $Target /E /NFL /NDL /NJH /NJS /R:1 /W:1
    $rc = $LASTEXITCODE
    if ($rc -ge 8) {
        throw ('复制文件失败（robocopy 退出码 ' + $rc + '）。请确认安装目录可写、杀毒软件没有拦截，然后重试。')
    }
    $copiedJar = Join-Path $Target 'OfferChaser.jar'
    if (-not (Test-Path -LiteralPath $copiedJar)) {
        throw '复制完成后没有找到 OfferChaser.jar，请重试或换一个安装目录。'
    }
    Write-Ok ('程序文件已复制到：' + $Target)
}

function Initialize-Database {
    # 首次安装时直接用干净模板初始化数据库（与 启动.bat 的兜底逻辑一致）。
    # 这样即使用户绕过启动脚本直接运行 jar，也能拿到 v13 结构的库。
    param([string]$Target)
    $dbFile = Join-Path $Target 'db\offerchaser.db'
    $template = Join-Path $Target 'db\offerchaser.db.template'
    if (Test-Path -LiteralPath $dbFile) { return }
    if (-not (Test-Path -LiteralPath $template)) { return }
    try {
        New-Item -ItemType Directory -Path (Split-Path $dbFile -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $template -Destination $dbFile -Force
        Write-Ok '已用干净模板初始化数据库（首次启动更快）'
    } catch {
        Write-Warn2 ('初始化数据库失败，首次启动时会自动处理：' + $_.Exception.Message)
    }
}

function New-Shortcuts {
    param([string]$Target)
    Write-Step '5/6' '创建快捷方式'
    if ($NoShortcut) { Write-Ok '已跳过（-NoShortcut）'; return }

    $vbs  = Join-Path $Target 'OfferChaser.vbs'
    $stop = Join-Path $Target '停止OfferChaser.vbs'
    $icon = Join-Path $Target 'front\app\favicon.ico'

    $desktop = Get-DesktopPath
    $created = @()
    if (New-Shortcut -LinkPath (Join-Path $desktop 'OfferChaser.lnk') -TargetPath $vbs -WorkingDirectory $Target -IconPath $icon -Description 'OfferChaser · BOSS 直聘投递助手') {
        $created += (Join-Path $desktop 'OfferChaser.lnk')
    }
    if (New-Shortcut -LinkPath (Join-Path $desktop '停止 OfferChaser.lnk') -TargetPath $stop -WorkingDirectory $Target -IconPath $icon -Description '停止 OfferChaser 后台服务') {
        $created += (Join-Path $desktop '停止 OfferChaser.lnk')
    }

    $programs = Get-StartMenuProgramsPath
    $group = Join-Path $programs 'OfferChaser'
    try { New-Item -ItemType Directory -Path $group -Force | Out-Null } catch { }
    if (New-Shortcut -LinkPath (Join-Path $group 'OfferChaser.lnk') -TargetPath $vbs -WorkingDirectory $Target -IconPath $icon -Description 'OfferChaser · BOSS 直聘投递助手') {
        $created += (Join-Path $group 'OfferChaser.lnk')
    }
    if (New-Shortcut -LinkPath (Join-Path $group '停止 OfferChaser.lnk') -TargetPath $stop -WorkingDirectory $Target -IconPath $icon -Description '停止 OfferChaser 后台服务') {
        $created += (Join-Path $group '停止 OfferChaser.lnk')
    }

    if ($created.Count -gt 0) { Write-Ok ('已创建 ' + $created.Count + ' 个快捷方式') }
}

function Start-OfferChaser {
    param([string]$Target)
    Write-Step '6/6' '启动 OfferChaser'
    if ($NoLaunch) { Write-Ok '已跳过（-NoLaunch）'; return }

    $url = 'http://127.0.0.1:6866/boss'
    if (Test-PortListening -Port 9527) {
        Write-Ok 'OfferChaser 已经在运行，直接打开管理台'
        try { Start-Process $url } catch { }
        return
    }

    $vbs = Join-Path $Target 'OfferChaser.vbs'
    try {
        Start-Process -FilePath $vbs -WorkingDirectory $Target | Out-Null
    } catch {
        throw ('启动失败：' + $_.Exception.Message + '。请双击安装目录里的「OfferChaser.vbs」重试。')
    }

    Write-Host '    等待服务启动（首次启动约 10-30 秒）...' -ForegroundColor DarkGray
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 1
        if (Test-PortListening -Port 9527) { $ready = $true; break }
    }
    if ($ready) {
        Write-Ok '服务已启动，管理台地址：' + $url
        Write-Host '    管理台会自动在浏览器打开；如果没有，请手动访问上面的地址。' -ForegroundColor DarkGray
    } else {
        Write-Warn2 '服务启动较慢或失败，请双击桌面「OfferChaser」再试，或查看日志：'
        Write-Warn2 (Join-Path $Target 'target\logs\offerchaser.log')
    }
}

function Write-Summary {
    param([string]$Target)
    Write-Title 'OfferChaser 安装完成'
    Write-Host '  这个版本不需要另装 Java / Node。' -ForegroundColor White
    Write-Host ''
    Write-Host ('  安装目录：' + $Target)
    Write-Host ('  管理台：  http://127.0.0.1:6866')
    Write-Host ('  后端 API：http://127.0.0.1:9527')
    Write-Host ('  说明书：  ' + (Join-Path $Target '安装说明书.md'))
    Write-Host ('  日志：    ' + (Join-Path $Target 'target\logs\offerchaser.log'))
    Write-Host ''
    Write-Host '  启动：双击桌面「OfferChaser」，或开始菜单里的 OfferChaser' -ForegroundColor White
    Write-Host '  停止：双击桌面「停止 OfferChaser」，或托盘图标右键退出' -ForegroundColor White
    Write-Host ''
    Write-Host '  下一步：' -ForegroundColor Cyan
    Write-Host '   1. 在弹出的 Chrome 窗口里扫码登录 BOSS；'
    Write-Host '   2. 管理台「配置」里填模型服务 API Key；'
    Write-Host '   3. 建一个方向模板并激活；'
    Write-Host '   4. 回「今日」手动点「开始投递」。'
    Write-Host ''
    Write-Host '  真实投递不会自动开始，必须由你手动点开始。' -ForegroundColor Yellow
    Write-Host ('=' * 62)
}

# ==================== 主流程 ====================
Write-Title 'OfferChaser 一键安装'
Write-Host '  安装包自带 Java 21 / Node / Patchright，无需另外安装。' -ForegroundColor DarkGray

try {
    Assert-Package
    Assert-Environment
    $target = Resolve-TargetDir
    Copy-Package -Target $target
    Initialize-Database -Target $target
    New-Shortcuts -Target $target
    Start-OfferChaser -Target $target
    Write-Summary -Target $target
    Wait-Key
    exit 0
} catch {
    Write-Fail '安装未完成'
    Write-Host ''
    Write-Host ('  原因：' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ''
    Write-Host '  排查建议：' -ForegroundColor Yellow
    Write-Host '   1. 确认 zip 已经完整解压，不是直接在压缩包里双击；'
    Write-Host '   2. 右键「一键安装.bat」-> 以管理员身份运行；'
    Write-Host '   3. 检查杀毒软件是否拦截了脚本，可先临时关闭或加白；'
    Write-Host '   4. 换一个安装目录（例如 D:\OfferChaser）；'
    Write-Host '   5. 仍不行请到 https://github.com/Yhmx6/offerchaser/issues 反馈，并附上截图。'
    Wait-Key
    exit 1
}
