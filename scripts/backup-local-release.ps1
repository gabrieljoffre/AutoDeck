param(
    [Parameter(Mandatory = $true)]
    [string]$VersionTag
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$destDir = "D:\car\multimidia\releases\$VersionTag"
$shareDir = "D:\car\multimidia\.apk-share"

New-Item -ItemType Directory -Force -Path $destDir | Out-Null
New-Item -ItemType Directory -Force -Path $shareDir | Out-Null

$appApkSrc = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$linkApkSrc = Join-Path $repoRoot "companion-app\build\outputs\apk\debug\companion-app-debug.apk"

if (-not (Test-Path $appApkSrc)) {
    throw "APK principal nao encontrado em: $appApkSrc"
}
if (-not (Test-Path $linkApkSrc)) {
    throw "APK companion nao encontrado em: $linkApkSrc"
}

$appApkDest = Join-Path $destDir "AutoDeck-$VersionTag.apk"
$linkApkDest = Join-Path $destDir "AutoDeck-Link-$VersionTag.apk"

Copy-Item -Force $appApkSrc $appApkDest
Copy-Item -Force $appApkSrc (Join-Path $shareDir "AutoDeck-debug.apk")

Copy-Item -Force $linkApkSrc $linkApkDest
Copy-Item -Force $linkApkSrc (Join-Path $shareDir "AutoDeck-Link-debug.apk")

$bundlePath = Join-Path $destDir "AutoDeck-$VersionTag.bundle"
git -C $repoRoot bundle create $bundlePath --all
if ($LASTEXITCODE -ne 0) {
    throw "Falha ao criar git bundle em $bundlePath"
}

$commitSha = (git -C $repoRoot rev-parse HEAD).Trim()
$appInfo = Get-Item $appApkDest
$linkInfo = Get-Item $linkApkDest
$appHash = (Get-FileHash -Algorithm SHA256 $appApkDest).Hash
$linkHash = (Get-FileHash -Algorithm SHA256 $linkApkDest).Hash
$nowStr = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"

$releaseInfo = @"
AutoDeck Local Release Backup
=============================
Tag: $VersionTag
Commit SHA: $commitSha
Date/Time: $nowStr

[1] AutoDeck Head-Unit APK
File: AutoDeck-$VersionTag.apk
Size (bytes): $($appInfo.Length)
SHA-256: $appHash

[2] AutoDeck Link Companion APK
File: AutoDeck-Link-$VersionTag.apk
Size (bytes): $($linkInfo.Length)
SHA-256: $linkHash

[3] Git Offline Bundle
File: AutoDeck-$VersionTag.bundle
"@

$infoPath = Join-Path $destDir "RELEASE_INFO.txt"
Set-Content -Path $infoPath -Value $releaseInfo -Encoding UTF8

Write-Host "Backup local concluido em: $destDir"
