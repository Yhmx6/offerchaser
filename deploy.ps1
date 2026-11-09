# 一键部署.ps1 -- 从 GitHub Releases 自动下载并安装 OfferChaser
#
# 用法（普通用户）：
#   双击仓库根目录的「一键部署.bat」。
#
# 高级用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\一键部署.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\一键部署.ps1 -Proxy http://127.0.0.1:7897
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\一键部署.ps1 -Dir D:\OfferChaser
#
# 参数：
#   -Proxy <url>    显式指定 HTTPS 代理（不填会自动尝试常见本机代理端口）
#   -Dir <路径>     透传给安装器，指定安装目录
#   -Repo <owner/name> 仓库名，默认 Yhmx6/offerchaser
#   -KeepTemp       保留下载和解压的临时文件（排障用）
#   -NoLaunch       安装完成后不启动程序
#
# 流程：查最新 Release -> 下载 win64 一键包 -> 校验 SHA256 -> 解压 -> 调用一键安装器。
# 需要能访问 GitHub；没有网络代理时可能失败，脚本会给出提示。

[CmdletBinding()]
param(
    [string]$Proxy = '',
    [string]$Dir = '',
    [string]$Repo = 'Yhmx6/offerchaser',
    [switch]$KeepTemp,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$ApiBase = 'https://api.github.com'
$UserAgent = 'OfferChaser-Deployer'

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

function Wait-Key {
    Write-Host ''
    Write-Host '按回车键退出...' -ForegroundColor DarkGray
    try { [void](Read-Host) } catch { }
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 300)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait($TimeoutMs)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-ProxyCandidates {
    $list = New-Object System.Collections.ArrayList
    if ($Proxy -and $Proxy.Trim()) {
        [void]$list.Add($Proxy.Trim())
    }
    foreach ($name in @('HTTPS_PROXY', 'https_proxy', 'HTTP_PROXY', 'http_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value -and $value.Trim()) { [void]$list.Add($value.Trim()) }
    }
    foreach ($port in @(7897, 7890, 10809, 10808, 1080, 8080)) {
        if (Test-TcpPort -HostName '127.0.0.1' -Port $port) {
            [void]$list.Add('http://127.0.0.1:' + $port)
        }
    }
    # 去重
    $seen = @{}
    $unique = New-Object System.Collections.ArrayList
    foreach ($item in $list) {
        $key = $item.ToLowerInvariant()
        if (-not $seen.ContainsKey($key)) {
            $seen[$key] = $true
            [void]$unique.Add($item)
        }
    }
    return $unique
}

function Invoke-GitHubJson {
    param([string]$Uri, [string]$ProxyUrl)
    $headers = @{ 'User-Agent' = $UserAgent; 'Accept' = 'application/vnd.github+json' }
    if ($ProxyUrl) {
        return Invoke-RestMethod -Uri $Uri -Headers $headers -Proxy $ProxyUrl -TimeoutSec 30 -UseBasicParsing
    }
    return Invoke-RestMethod -Uri $Uri -Headers $headers -TimeoutSec 30 -UseBasicParsing
}

function Get-ReleaseInfo {
    Write-Step '1/6' '查询 GitHub 最新版本'
    $apiUrl = $ApiBase + '/repos/' + $Repo + '/releases/latest'
    $candidates = @('')
    foreach ($item in (Get-ProxyCandidates)) { $candidates += $item }
    $lastError = ''
    foreach ($proxyUrl in $candidates) {
        try {
            if ($proxyUrl) {
                Write-Host ('    尝试通过代理访问：' + $proxyUrl) -ForegroundColor DarkGray
                $release = Invoke-GitHubJson -Uri $apiUrl -ProxyUrl $proxyUrl
            } else {
                Write-Host '    尝试直连 GitHub...' -ForegroundColor DarkGray
                $release = Invoke-GitHubJson -Uri $apiUrl -ProxyUrl ''
            }
            if ($release -and $release.tag_name) {
                Write-Ok ('最新版本：' + $release.tag_name)
                return $release
            }
        } catch {
            $lastError = $_.Exception.Message
            if ($proxyUrl) { Write-Host ('    代理不可用：' + $proxyUrl) -ForegroundColor DarkGray }
        }
    }
    throw ('无法连接到 GitHub API。请检查网络，或在环境变量里设置 HTTPS_PROXY（例如 http://127.0.0.1:7897）后重试。最后错误：' + $lastError)
}

function Find-Asset {
    param($Release, [string]$Pattern)
    foreach ($asset in $Release.assets) {
        if ($asset.name -match $Pattern) { return $asset }
    }
    return $null
}

function Invoke-Download {
    param([string]$Uri, [string]$OutFile, [string]$ProxyUrl)
    if ($ProxyUrl) {
        Invoke-WebRequest -Uri $Uri -OutFile $OutFile -Proxy $ProxyUrl -UseBasicParsing -TimeoutSec 600
    } else {
        Invoke-WebRequest -Uri $Uri -OutFile $OutFile -UseBasicParsing -TimeoutSec 600
    }
}

function Find-PackageRoot {
    param([string]$ExtractDir)
    $direct = Join-Path $ExtractDir '一键安装.bat'
    if (Test-Path -LiteralPath $direct) { return $ExtractDir }
    $dirs = @(Get-ChildItem -LiteralPath $ExtractDir -Directory -ErrorAction SilentlyContinue)
    foreach ($d in $dirs) {
        $candidate = Join-Path $d.FullName '一键安装.bat'
        if (Test-Path -LiteralPath $candidate) { return $d.FullName }
    }
    foreach ($d in $dirs) {
        $bat = @(Get-ChildItem -LiteralPath $d.FullName -Filter '一键安装.bat' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1)
        if ($bat.Count -gt 0) {
            $found = $bat[0]
            if (Test-Path -LiteralPath (Join-Path (Split-Path $found.FullName -Parent) 'OfferChaser.jar')) {
                return (Split-Path $found.FullName -Parent)
            }
        }
    }
    return $null
}

function Use-WorkingProxy([object[]]$Candidates, [scriptblock]$Action) {
    $lastError = ''
    foreach ($proxyUrl in $Candidates) {
        try {
            & $Action $proxyUrl
            return
        } catch {
            $lastError = $_.Exception.Message
            if ($proxyUrl) { Write-Host ('    代理下载失败，换下一个：' + $proxyUrl) -ForegroundColor DarkGray }
        }
    }
    throw $lastError
}

$workRoot = Join-Path $env:TEMP ('OfferChaser-deploy-' + [Guid]::NewGuid().ToString('N'))
$zipPath = Join-Path $workRoot 'OfferChaser.zip'
$sumsPath = Join-Path $workRoot 'SHA256SUMS.txt'
$extractDir = Join-Path $workRoot 'extract'

try {
    Write-Title 'OfferChaser 一键部署'
    Write-Host '  从 GitHub Releases 下载最新一键包，校验后自动安装。' -ForegroundColor DarkGray

    $release = Get-ReleaseInfo
    $zipAsset = Find-Asset -Release $release -Pattern 'win64\.zip$'
    if (-not $zipAsset) {
        $names = ($release.assets | ForEach-Object { $_.name }) -join '、'
        throw ('最新版本里没有找到 Windows 一键包（*win64.zip）。现有资产：' + $names)
    }
    $sumsAsset = Find-Asset -Release $release -Pattern 'SHA256SUMS\.txt$'
    Write-Ok ('找到一键包：' + $zipAsset.name + '（' + [math]::Round($zipAsset.size / 1MB, 1) + ' MB）')

    Write-Step '2/6' '准备临时目录'
    New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
    Write-Ok ('临时目录：' + $workRoot)

    Write-Step '3/6' '下载一键包'
    $proxyCandidates = @('')
    foreach ($item in (Get-ProxyCandidates)) { $proxyCandidates += $item }
    $downloadOk = $false
    $usedProxy = ''
    foreach ($proxyUrl in $proxyCandidates) {
        try {
            Write-Host ('    下载中' + $(if ($proxyUrl) { '（代理 ' + $proxyUrl + '）' } else { '（直连）' }) + '...') -ForegroundColor DarkGray
            Invoke-Download -Uri $zipAsset.browser_download_url -OutFile $zipPath -ProxyUrl $proxyUrl
            $downloadOk = $true
            $usedProxy = $proxyUrl
            break
        } catch {
            Write-Host ('    本次下载失败：' + $_.Exception.Message) -ForegroundColor DarkGray
            if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue }
        }
    }
    if (-not $downloadOk) {
        throw '一键包下载失败。请检查网络或代理设置后重试；也可以直接到 Releases 页面手动下载 zip。'
    }
    $downloadedSize = (Get-Item -LiteralPath $zipPath).Length
    Write-Ok ('下载完成：' + [math]::Round($downloadedSize / 1MB, 1) + ' MB')

    Write-Step '4/6' '校验 SHA256'
    if ($sumsAsset) {
        try {
            Invoke-Download -Uri $sumsAsset.browser_download_url -OutFile $sumsPath -ProxyUrl $usedProxy
            $sumsText = Get-Content -LiteralPath $sumsPath -Raw -Encoding UTF8
            $expected = ''
            foreach ($line in ($sumsText -split "`r?`n")) {
                if ($line -match '^\s*([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') {
                    $hash = $Matches[1]
                    $name = $Matches[2]
                    if ([System.IO.Path]::GetFileName($zipAsset.name) -eq $name -or $name -eq $zipAsset.name) {
                        $expected = $hash
                        break
                    }
                }
            }
            if (-not $expected) { throw 'SHA256SUMS.txt 里没有这个包的校验值' }
            $actual = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
            if ($actual.ToUpperInvariant() -ne $expected.ToUpperInvariant()) {
                throw ('SHA256 不一致：期望 ' + $expected + '，实际 ' + $actual)
            }
            Write-Ok 'SHA256 校验通过'
        } catch {
            throw ('校验失败，已中止安装（可能是下载损坏）：' + $_.Exception.Message + '。请重新运行一键部署，或手动下载 zip。')
        }
    } else {
        Write-Warn2 'Release 里没有 SHA256SUMS.txt，跳过校验'
    }

    Write-Step '5/6' '解压'
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $extractDir)
    $packageRoot = Find-PackageRoot -ExtractDir $extractDir
    if (-not $packageRoot) { throw '解压后没有找到「一键安装.bat」，压缩包可能不完整。' }
    Write-Ok ('解压完成：' + $packageRoot)

    Write-Step '6/6' '运行安装器'
    $installer = Join-Path $packageRoot '一键安装.bat'
    $args = @('-NoPause')
    if ($Dir -and $Dir.Trim()) { $args += @('-Dir', $Dir.Trim()) } else { $args += '-Auto' }
    if ($NoLaunch) { $args += '-NoLaunch' }
    $argLine = ($args | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', ('"' + $installer + '" ' + $argLine)) -WorkingDirectory $packageRoot -Wait -PassThru -NoNewWindow
    if ($proc.ExitCode -ne 0) {
        throw ('安装器返回失败（退出码 ' + $proc.ExitCode + '）。请到 Releases 页面手动下载 zip，解压后双击「一键安装.bat」。')
    }

    Write-Host ''
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
    Write-Host '  部署完成' -ForegroundColor Green
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
    Write-Host ('  版本：' + $release.tag_name)
    Write-Host '  管理台：http://127.0.0.1:6866'
    Write-Host '  说明书：安装目录里的安装说明书.md'
    if (-not $KeepTemp) {
        try { Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
    } else {
        Write-Host ('  临时文件已保留：' + $workRoot)
    }
    Write-Host ''
    if (-not $NoLaunch) {
        Write-Host '  如果浏览器没有自动打开管理台，请手动访问上面的地址。' -ForegroundColor DarkGray
    }
    Wait-Key
    exit 0
} catch {
    Write-Host ''
    Write-Host ('    X   ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ''
    Write-Host '  排查建议：' -ForegroundColor Yellow
    Write-Host '   1. 确认能打开 https://github.com/Yhmx6/offerchaser/releases；'
    Write-Host '   2. 国内网络可先设置代理：set HTTPS_PROXY=http://127.0.0.1:7897 后重试；'
    Write-Host '   3. 或直接手动下载 Release 里的 OfferChaser-2.0.1-win64.zip；'
    Write-Host '   4. 解压后双击「一键安装.bat」。'
    Write-Host ''
    Wait-Key
    exit 1
}
