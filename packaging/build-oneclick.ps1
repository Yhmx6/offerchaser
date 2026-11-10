<#
  build-oneclick.ps1 -- 组装 GitHub Release 用的 Windows x64 一键包

  输入是一个“能直接运行”的完整目录（RuntimeDir），必须包含：
    OfferChaser.jar
    runtime\bin\java.exe
    node\node.exe
    patchright-driver\package\cli.js
    third-party\
    src\main\resources\dist\
    front\app\favicon.ico
    db\offerchaser.db.template
    启动.bat / OfferChaser.vbs / 停止OfferChaser.vbs / OfferChaser.ps1
    （可选）启动-显示日志.bat

  脚本只按白名单拷贝，RuntimeDir 里的 db\offerchaser.db、data\、browser-data\、target\
  一律不会被带进发布包。

  用法（在仓库根目录执行）：
    powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\build-oneclick.ps1 `
        -RuntimeDir "D:\OfferChaser\release\OfferChaser-2.0.1"

    # 指定版本与输出目录
    .\packaging\build-oneclick.ps1 -RuntimeDir "..." -Version 2.0.1 -OutDir ".\release"

    # 只组装不压 zip
    .\packaging\build-oneclick.ps1 -RuntimeDir "..." -SkipZip

  产出：
    <OutDir>\OfferChaser-<Version>-win64.zip          一键包
    <OutDir>\SHA256SUMS.txt                           这个 zip 的校验值（Release 资产）
    <OutDir>\OfferChaser-<Version>\                   解压后的一键包目录（便于检查）

  说明：
    * jar 会做一次“瘦身”：移除未使用的 BOOT-INF/lib/driver-bundle-1.62.0.jar
      （运行时始终通过 -Dplaywright.cli.dir 使用外置 patchright-driver），
      同时保留 Spring Boot 嵌套 jar 必须的 STORED 压缩方式并改写 classpath.idx。
    * jar 内会注入 META-INF/LICENSE 与 META-INF/NOTICE（PolyForm 分发要求）。
    * 用 -SkipJarPrune 可以保留完整 driver-bundle（包会大约 190 MB 更大）。
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RuntimeDir,
    [string]$Version = '2.0.1',
    [string]$OutDir = '',
    [switch]$SkipZip,
    [switch]$SkipJarPrune
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $RepoRoot) { $RepoRoot = (Get-Location).Path }
$RuntimeDir = [System.IO.Path]::GetFullPath($RuntimeDir).TrimEnd('\')
if (-not $OutDir) { $OutDir = Join-Path $RepoRoot 'release' }
$OutDir = [System.IO.Path]::GetFullPath($OutDir).TrimEnd('\')

$Name = 'OfferChaser-' + $Version
$Stage = Join-Path $OutDir $Name
$ZipPath = Join-Path $OutDir ($Name + '-win64.zip')
$SumsAsset = Join-Path $OutDir 'SHA256SUMS.txt'

function Write-Step($n, $text) {
    Write-Host ''
    Write-Host ('[' + $n + '] ' + $text) -ForegroundColor Yellow
}

function Write-Ok($text) { Write-Host ('    OK  ' + $text) -ForegroundColor Green }
function Write-Info($text) { Write-Host ('        ' + $text) -ForegroundColor DarkGray }

function Assert-Input {
    Write-Step '1/8' '检查输入目录'
    $required = @(
        'OfferChaser.jar',
        'runtime\bin\java.exe',
        'node\node.exe',
        'patchright-driver\package\cli.js',
        'src\main\resources\dist\index.html',
        'front\app\favicon.ico',
        'db\offerchaser.db.template',
        '启动.bat',
        'OfferChaser.vbs',
        '停止OfferChaser.vbs',
        'OfferChaser.ps1'
    )
    $missing = @()
    foreach ($rel in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $RuntimeDir $rel))) { $missing += $rel }
    }
    if ($missing.Count -gt 0) {
        throw ('RuntimeDir 缺少运行必需文件：' + ($missing -join '、') + "`n请指向一个能直接运行 OfferChaser 的完整目录。")
    }
    $repoRequired = @('LICENSE', 'NOTICE', '安装说明书.md', 'README.md', 'install.ps1', '一键安装.bat')
    foreach ($f in $repoRequired) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $f))) { throw ('仓库缺少文件：' + $f) }
    }
    Write-Ok ('输入目录：' + $RuntimeDir)
    Write-Ok ('仓库目录：' + $RepoRoot)
}

function Copy-WhitelistDirs {
    Write-Step '2/8' '按白名单拷贝运行目录'
    foreach ($rel in @('runtime', 'node', 'patchright-driver', 'third-party')) {
        $from = Join-Path $RuntimeDir $rel
        $to = Join-Path $Stage $rel
        New-Item -ItemType Directory -Path (Split-Path $to -Parent) -Force | Out-Null
        $null = robocopy $from $to /E /NFL /NDL /NJH /NJS /R:1 /W:1
        if ($LASTEXITCODE -ge 8) { throw ('robocopy 失败：' + $rel) }
        Write-Ok ($rel + '\')
    }

    $distFrom = Join-Path $RuntimeDir 'src\main\resources\dist'
    $distTo = Join-Path $Stage 'src\main\resources\dist'
    New-Item -ItemType Directory -Path $distTo -Force | Out-Null
    $null = robocopy $distFrom $distTo /E /NFL /NDL /NJH /NJS /R:1 /W:1
    if ($LASTEXITCODE -ge 8) { throw 'robocopy 失败：src\main\resources\dist' }
    Write-Ok 'src\main\resources\dist\'

    $iconFrom = Join-Path $RuntimeDir 'front\app\favicon.ico'
    $iconTo = Join-Path $Stage 'front\app\favicon.ico'
    New-Item -ItemType Directory -Path (Split-Path $iconTo -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $iconFrom -Destination $iconTo -Force
    Write-Ok 'front\app\favicon.ico'

    foreach ($f in @('启动.bat', 'OfferChaser.vbs', '停止OfferChaser.vbs', 'OfferChaser.ps1')) {
        Copy-Item -LiteralPath (Join-Path $RuntimeDir $f) -Destination (Join-Path $Stage $f) -Force
        Write-Ok $f
    }

    # 排障入口（启动.bat --console 的壳），运行目录里没有就跳过，不阻塞组包。
    $consoleLaunch = '启动-显示日志.bat'
    if (Test-Path -LiteralPath (Join-Path $RuntimeDir $consoleLaunch)) {
        Copy-Item -LiteralPath (Join-Path $RuntimeDir $consoleLaunch) -Destination (Join-Path $Stage $consoleLaunch) -Force
        Write-Ok $consoleLaunch
    } else {
        Write-Info ($consoleLaunch + ' 不在运行目录里，跳过（可用 启动.bat --console 代替）')
    }

    New-Item -ItemType Directory -Path (Join-Path $Stage 'db') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $RuntimeDir 'db\offerchaser.db.template') -Destination (Join-Path $Stage 'db\offerchaser.db.template') -Force
    Write-Ok 'db\offerchaser.db.template'
}

function New-SlimJar {
    Write-Step '3/8' '生成发布用 jar'
    $srcJar = Join-Path $RuntimeDir 'OfferChaser.jar'
    $dstJar = Join-Path $Stage 'OfferChaser.jar'
    $skipEntry = 'BOOT-INF/lib/driver-bundle-1.62.0.jar'

    if ($SkipJarPrune) {
        Write-Info '已指定 -SkipJarPrune，直接使用完整 jar'
        Copy-Item -LiteralPath $srcJar -Destination $dstJar -Force
    } else {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        Add-Type -AssemblyName System.IO.Compression
        $in = [System.IO.Compression.ZipFile]::OpenRead($srcJar)
        $out = [System.IO.Compression.ZipFile]::Open($dstJar, 'Create')
        $removed = $false
        try {
            foreach ($e in $in.Entries) {
                if ($e.FullName -eq $skipEntry) { $removed = $true; continue }
                # Spring Boot 3.x 的嵌套 jar 必须是 STORED，否则启动时报 NoClassDefFoundError
                $isStored = ($e.CompressedLength -eq $e.Length) -and ($e.Length -gt 0)
                $level = if ($isStored) { [System.IO.Compression.CompressionLevel]::NoCompression } else { [System.IO.Compression.CompressionLevel]::Optimal }
                $ne = $out.CreateEntry($e.FullName, $level)
                $ne.LastWriteTime = $e.LastWriteTime

                if ($e.FullName -eq 'BOOT-INF/classpath.idx') {
                    $sr = New-Object System.IO.StreamReader($e.Open())
                    $text = $sr.ReadToEnd(); $sr.Dispose()
                    $lines = @(($text -split "`n") | ForEach-Object { $_.TrimEnd([char]13) } | Where-Object { $_.Trim() -ne '' -and ($_ -notmatch [regex]::Escape($skipEntry)) })
                    $sw = New-Object System.IO.StreamWriter($ne.Open())
                    $sw.Write((($lines -join "`n") + "`n")); $sw.Dispose()
                    continue
                }

                $s = $e.Open(); $t = $ne.Open()
                try { $s.CopyTo($t) } finally { $t.Dispose(); $s.Dispose() }
            }
        } finally {
            $out.Dispose(); $in.Dispose()
        }
        if (-not $removed) { Write-Info '输入 jar 里没有 driver-bundle，跳过未使用依赖的移除' }
        Write-Ok ('已移除未使用依赖：' + $skipEntry)
    }

    # 注入 LICENSE / NOTICE（PolyForm 的 Notices 条款要求）
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $zip = [System.IO.Compression.ZipFile]::Open($dstJar, 'Update')
    try {
        foreach ($f in @('LICENSE', 'NOTICE')) {
            $entry = 'META-INF/' + $f
            $old = $zip.GetEntry($entry)
            if ($null -ne $old) { $old.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $RepoRoot $f), $entry) | Out-Null
        }
    } finally { $zip.Dispose() }
    Write-Ok 'jar 内已包含 META-INF/LICENSE 与 META-INF/NOTICE'

    $jarMB = [math]::Round((Get-Item $dstJar).Length / 1MB, 1)
    Write-Ok ('jar 体积：' + $jarMB + ' MB')
}

function Copy-DocsAndInstallers {
    Write-Step '4/8' '补入文档与一键安装脚本'
    foreach ($f in @('安装说明书.md', 'README.md', '项目说明.md', 'LICENSE', 'NOTICE', 'DISCLAIMER.md', 'AUTHORS.md', 'ATTRIBUTION.md', 'CITATION.cff', '一键安装.bat', 'install.ps1')) {
        Copy-Item -LiteralPath (Join-Path $RepoRoot $f) -Destination (Join-Path $Stage $f) -Force
        Write-Ok $f
    }
    $null = robocopy (Join-Path $RepoRoot 'doc') (Join-Path $Stage 'doc') /E /NFL /NDL /NJH /NJS /R:1 /W:1
    if ($LASTEXITCODE -ge 8) { throw 'robocopy 失败：doc' }
    Write-Ok 'doc\'
}

function New-ReleaseJson {
    Write-Step '5/8' '生成 release.json'
    $jarHash = (Get-FileHash -LiteralPath (Join-Path $Stage 'OfferChaser.jar') -Algorithm SHA256).Hash.ToUpperInvariant()
    $gitCommit = ''
    try {
        Push-Location $RepoRoot
        $gitCommit = (& git rev-parse HEAD 2>$null).Trim()
    } catch { $gitCommit = '' }
    finally { Pop-Location }
    $installedMB = [math]::Round(((Get-ChildItem -LiteralPath $Stage -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB), 1)

    $release = [ordered]@{
        product         = 'OfferChaser'
        version         = $Version
        platform        = 'windows-x64'
        package         = ($Name + '-win64.zip')
        builtAt         = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
        gitCommit       = $gitCommit
        jarSha256       = $jarHash
        javaVersion     = 'Temurin 21.0.13+9'
        installedSizeMB = $installedMB
        license         = 'PolyForm Noncommercial License 1.0.0'
        requiredNotice  = @(
            'Required Notice: Copyright (c) 2024-2026 GET JOBS (https://github.com/loks666/get_jobs)',
            'Required Notice: Copyright (c) 2026 Yhmx6 (https://github.com/Yhmx6/offerchaser)'
        )
    }
    $json = $release | ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText((Join-Path $Stage 'release.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok ('jar SHA256：' + $jarHash)
    Write-Ok ('安装体积：' + $installedMB + ' MB')
}

function New-SumsFile {
    Write-Step '6/8' '生成包内 SHA256SUMS.txt'
    $sumsPath = Join-Path $Stage 'SHA256SUMS.txt'
    $files = @(Get-ChildItem -LiteralPath $Stage -Recurse -File -Force |
        Where-Object { $_.FullName -ne $sumsPath } | Sort-Object FullName)
    $lines = foreach ($f in $files) {
        $rel = $f.FullName.Substring($Stage.Length + 1).Replace('\', '/')
        $h = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        ($h + '  ' + $rel)
    }
    [System.IO.File]::WriteAllText($sumsPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok ('清单行数：' + $files.Count)
}

function Test-Package {
    Write-Step '7/8' '发布包自检'
    foreach ($bad in @('db\offerchaser.db', 'data', 'browser-data', 'target')) {
        if (Test-Path -LiteralPath (Join-Path $Stage $bad)) { throw ('发布包里出现敏感路径：' + $bad) }
    }
    Write-Ok '无 db 个人库 / data / browser-data / target'

    $zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $Stage 'OfferChaser.jar'))
    $libCount = 0
    try {
        foreach ($f in @('META-INF/LICENSE', 'META-INF/NOTICE')) {
            if ($null -eq $zip.GetEntry($f)) { throw ('jar 里缺少 ' + $f) }
        }
        if ($null -eq $zip.GetEntry('BOOT-INF/classes/application.yaml')) { throw 'jar 结构异常：找不到 application.yaml' }
        $libs = @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' -and $_.Length -gt 0 })
        $libCount = $libs.Count
        $badLibs = @($libs | Where-Object { $_.CompressedLength -ne $_.Length })
        if ($badLibs.Count -gt 0) { throw ('嵌套 jar 未按 STORED 存储：' + $badLibs.Count + ' 个') }
        $idx = $zip.GetEntry('BOOT-INF/classpath.idx')
        if ($idx) {
            $sr = New-Object System.IO.StreamReader($idx.Open()); $idxText = $sr.ReadToEnd(); $sr.Dispose()
            if ($idxText -match 'driver-bundle') { throw 'classpath.idx 仍引用已移除的 driver-bundle' }
        }
    } finally { $zip.Dispose() }
    Write-Ok ('jar 自检通过（嵌套 lib 数：' + $libCount + '）')
}

function New-Zip {
    Write-Step '8/8' '压缩'
    if ($SkipZip) { Write-Info '已指定 -SkipZip，跳过压缩'; return }
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $t0 = Get-Date
    [System.IO.Compression.ZipFile]::CreateFromDirectory($Stage, $ZipPath, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    $zipMB = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
    $zipHash = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Ok ('zip：' + $ZipPath)
    Write-Ok ('体积：' + $zipMB + ' MB，耗时 ' + [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) + ' 秒')
    Write-Ok ('SHA256：' + $zipHash)

    # Release 资产：只包含 zip 的校验值，供 一键部署 校验下载
    [System.IO.File]::WriteAllText($SumsAsset, ($zipHash + '  ' + (Split-Path $ZipPath -Leaf) + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok ('Release 校验文件：' + $SumsAsset)
}

# ==================== 主流程 ====================
Write-Host ''
Write-Host ('=' * 62) -ForegroundColor DarkCyan
Write-Host ('  OfferChaser ' + $Version + ' 一键包组装') -ForegroundColor Cyan
Write-Host ('=' * 62) -ForegroundColor DarkCyan

if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

Assert-Input
Copy-WhitelistDirs
New-SlimJar
Copy-DocsAndInstallers
New-ReleaseJson
New-SumsFile
Test-Package
New-Zip

Write-Host ''
$resultPath = $ZipPath
if ($SkipZip) { $resultPath = $Stage }
Write-Host ('完成：' + $resultPath) -ForegroundColor Green
