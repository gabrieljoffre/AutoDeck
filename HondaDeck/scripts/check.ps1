# Modeled on thtbee/nectarlink/scripts/check.ps1 + pierridotite/mobile-agent-testers/scripts/a11y.py
param(
    [switch]$Fast
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path (Split-Path -Parent $root) ".tools"

if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $toolsRoot "jdk21\jdk-21.0.13.1+2"))) {
    $env:JAVA_HOME = Join-Path $toolsRoot "jdk21\jdk-21.0.13.1+2"
}
if (-not $env:GRADLE_USER_HOME -and (Test-Path (Join-Path $toolsRoot "gradle-home"))) {
    $env:GRADLE_USER_HOME = Join-Path $toolsRoot "gradle-home"
}
if (-not $env:TEMP -or $env:TEMP -notmatch "multimidia") {
    $tmpDir = Join-Path $toolsRoot "tmp"
    if (Test-Path $tmpDir) {
        $env:TEMP = $tmpDir
        $env:TMP = $tmpDir
    }
}

Push-Location $root
$results = New-Object System.Collections.Generic.List[object]

function Invoke-Step {
    param([string]$Name, [scriptblock]$Body)
    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $ok = $true
    $global:LASTEXITCODE = 0
    $ErrorActionPreference = "Continue"
    try {
        & $Body
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    } catch {
        Write-Host $_ -ForegroundColor Red
        $ok = $false
    }
    $results.Add([pscustomobject]@{
        Step    = $Name
        Ok      = $ok
        Seconds = [math]::Round($timer.Elapsed.TotalSeconds, 1)
    })
}

try {
    Invoke-Step "Core protocol tests (:core-protocol:test)" {
        & .\gradlew.bat --no-daemon --console=plain :core-protocol:test
    }

    Invoke-Step "App unit & mobile-agent tests (:app:testDebugUnitTest)" {
        & .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest
    }

    Invoke-Step "Android Lint (:app:lintDebug)" {
        & .\gradlew.bat --no-daemon --console=plain :app:lintDebug
    }

    if (-not $Fast) {
        Invoke-Step "Debug APK build (:app:assembleDebug)" {
            & .\gradlew.bat --no-daemon --console=plain :app:assembleDebug
        }
    }

    Invoke-Step "mobile-agent-testers a11y self-check" {
        python (Join-Path $root 'tools\mobile-agent-testers\test_a11y.py')
    }
} finally {
    Pop-Location
}

Write-Host ""
$results | Format-Table -AutoSize @{ L = "Step"; E = { $_.Step } }, @{ L = "Result"; E = { if ($_.Ok) { "ok" } else { "FAILED" } } }, @{ L = "Time (s)"; E = { $_.Seconds } }
$failed = @($results | Where-Object { -not $_.Ok }).Count
if ($failed -gt 0) {
    Write-Host "$failed step(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host "All HondaDeck NectarLink + Mobile Agent checks passed." -ForegroundColor Green
