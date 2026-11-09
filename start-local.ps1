$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE/.gradle/jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path "$($_.FullName)/bin/java.exe" } | Select-Object -First 1
    if (-not $jdk) { throw 'Please install JDK 21 and configure JAVA_HOME.' }
    $env:JAVA_HOME = $jdk.FullName
}
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:SERVER_ADDRESS = '127.0.0.1'
if ($env:JAVA_TOOL_OPTIONS -notmatch 'jdk.net.unixdomain.tmpdir') {
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$env:SystemRoot\Temp".Trim()
}

# 源码仓库只提交干净模板；首次运行复制为真实库，绝不把真实库提交到 Git。
$dbDir = Join-Path $PSScriptRoot 'db'
$dbFile = Join-Path $dbDir 'offerchaser.db'
$dbTemplate = Join-Path $dbDir 'offerchaser.db.template'
New-Item -ItemType Directory -Path $dbDir -Force | Out-Null
if (-not (Test-Path -LiteralPath $dbFile)) {
    if (-not (Test-Path -LiteralPath $dbTemplate)) {
        throw 'Missing db/offerchaser.db.template. Pull the latest source or download the template from Releases.'
    }
    Copy-Item -LiteralPath $dbTemplate -Destination $dbFile -Force
    Write-Host '已从干净模板创建 db/offerchaser.db'
}

& ./gradlew.bat bootRun @args
exit $LASTEXITCODE
