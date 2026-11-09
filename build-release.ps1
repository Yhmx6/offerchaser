<#
  build-release.ps1 -- 生成一个可以安全分发的发布包

  它做三件事：
    1. 只按白名单拷文件，db/ data/ browser-data/ target/ 一律不进包；
    2. 把 LICENSE 和 NOTICE 注入 jar 的 META-INF（满足 PolyForm 的 Notices 条款）；
    3. 打包后自检，发现敏感目录或缺失许可证就直接失败。

  用法：
    .\build-release.ps1              生成 release\OfferChaser-2.0.1\
    .\build-release.ps1 -Zip         额外压成 release\OfferChaser-2.0.1.zip
    .\build-release.ps1 -CheckOnly   只做前置检查，不生成任何东西
#>
param(
    [switch]$Zip,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$Name    = 'OfferChaser-2.0.1'
$OutBase = Join-Path $Root 'release'
$OutDir  = Join-Path $OutBase $Name
$ZipPath = Join-Path $OutBase ($Name + '.zip')

$Dirs = @('runtime', 'node', 'patchright-driver', 'third-party')

$Files = @(
    'OfferChaser.jar',
    'LICENSE',
    'NOTICE',
    'README.md',
    'AUTHORS.md',
    'ATTRIBUTION.md',
    'CITATION.cff'
)

# 启动脚本按扩展名收，避免在脚本里写非 ASCII 文件名
$LauncherExt = @('.vbs', '.bat', '.ps1')
$Launchers = @(Get-ChildItem -LiteralPath $Root -File |
    Where-Object { $LauncherExt -contains $_.Extension.ToLower() -and $_.Name -ne 'build-release.ps1' })

# 首次启动要靠这份干净模板库复制出 db/offerchaser.db
$Template = 'db\offerchaser.db.template'

# 出现在发布包里就说明打包逻辑写错了
$Forbidden = @('db\offerchaser.db', 'data', 'browser-data', 'target', 'src', 'front', 'doc', '.git')

function Write-Step($n, $msg) { Write-Host ("[{0}] {1}" -f $n, $msg) }

# ---------- 1. 前置检查 ----------
Write-Step '1/5' '检查源文件'
foreach ($f in $Files) {
    $p = Join-Path $Root $f
    if (-not (Test-Path -LiteralPath $p)) { throw ("缺少必需文件: " + $f) }
}
if (-not (Test-Path -LiteralPath (Join-Path $Root $Template))) { throw ("缺少模板库: " + $Template) }
if ($Launchers.Count -lt 3) { throw "启动脚本不全，只找到 $($Launchers.Count) 个（.vbs/.bat/.ps1）" }
if (-not (Test-Path -LiteralPath (Join-Path $Root 'OfferChaser.ps1'))) { throw '缺少 OfferChaser.ps1，托盘启动器跑不起来' }

foreach ($f in @('LICENSE', 'NOTICE')) {
    $txt = Get-Content -LiteralPath (Join-Path $Root $f) -Raw -Encoding UTF8
    if ($txt -notmatch 'Required Notice:') { throw ($f + ' 里找不到 Required Notice 行') }
}

Write-Host '    源文件齐全，LICENSE / NOTICE 均含 Required Notice'

if ($CheckOnly) {
    Write-Host ''
    Write-Host '前置检查通过。未生成发布包（-CheckOnly）。'
    return
}

# ---------- 2. 拷贝白名单 ----------
Write-Step '2/5' '按白名单拷贝'
if (Test-Path -LiteralPath $OutDir) { Remove-Item -LiteralPath $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

foreach ($d in $Dirs) {
    $src = Join-Path $Root $d
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $OutDir $d) -Recurse -Force
        Write-Host ('    + ' + $d + '\')
    }
}
foreach ($f in $Files) {
    Copy-Item -LiteralPath (Join-Path $Root $f) -Destination (Join-Path $OutDir $f) -Force
    Write-Host ('    + ' + $f)
}
foreach ($l in $Launchers) {
    Copy-Item -LiteralPath $l.FullName -Destination (Join-Path $OutDir $l.Name) -Force
    Write-Host ('    + ' + $l.Name)
}
New-Item -ItemType Directory -Path (Join-Path $OutDir 'db') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $Root $Template) -Destination (Join-Path $OutDir $Template) -Force
Write-Host ('    + ' + $Template)

# ---------- 3. 注入许可证 ----------
Write-Step '3/5' '把 LICENSE / NOTICE 注入 jar 的 META-INF'
$jar = Join-Path $OutDir 'OfferChaser.jar'
$zip = [System.IO.Compression.ZipFile]::Open($jar, 'Update')
try {
    foreach ($f in @('LICENSE', 'NOTICE')) {
        $entry = 'META-INF/' + $f
        $old = $zip.GetEntry($entry)
        if ($null -ne $old) { $old.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, (Join-Path $Root $f), $entry) | Out-Null
        Write-Host ('    + jar!/' + $entry)
    }
}
finally {
    $zip.Dispose()
}

# ---------- 4. 自检 ----------
Write-Step '4/5' '自检发布包'
$problems = @()

foreach ($bad in $Forbidden) {
    if (Test-Path -LiteralPath (Join-Path $OutDir $bad)) { $problems += ('不该存在的路径: ' + $bad) }
}

$zip2 = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($f in @('LICENSE', 'NOTICE')) {
        if ($null -eq $zip2.GetEntry('META-INF/' + $f)) { $problems += ('jar 内缺少 META-INF/' + $f) }
    }
    $hasApp = $null -ne $zip2.GetEntry('BOOT-INF/classes/application.yaml')
    if (-not $hasApp) { $problems += 'jar 结构异常：找不到 BOOT-INF/classes/application.yaml' }
}
finally {
    $zip2.Dispose()
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host ('    ! ' + $_) -ForegroundColor Red }
    throw '自检失败，发布包不可用'
}
Write-Host '    自检通过：无敏感目录，jar 内已含 META-INF/LICENSE 与 META-INF/NOTICE'

$size = (Get-ChildItem -LiteralPath $OutDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("    发布包体积: {0:N0} MB" -f ($size / 1MB))

# ---------- 5. 可选压缩 ----------
if ($Zip) {
    Write-Step '5/5' '压缩'
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force }
    Compress-Archive -Path $OutDir -DestinationPath $ZipPath -CompressionLevel Optimal
    Write-Host ("    生成 {0}（{1:N0} MB）" -f $ZipPath, ((Get-Item $ZipPath).Length / 1MB))
}
else {
    Write-Step '5/5' '跳过压缩（需要 zip 请加 -Zip）'
}

Write-Host ''
Write-Host ('完成：' + $OutDir)
