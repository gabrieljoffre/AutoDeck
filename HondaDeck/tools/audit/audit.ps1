# Script único para executar a auditoria automática do HondaDeck 3.0 no tablet (Fase 7).
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$Install,
  [switch]$NoStart,
  [string]$ApkPath = '',
  [string]$Only = '',
  [string]$Serial = '',
  [string]$OutDir = 'D:\car\multimidia\HondaDeck\results\v3',
  [int]$Port = 9222,
  [int]$TimeoutMs = 12000
)

$ErrorActionPreference = 'Stop'
$auditDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$adbDefault = 'D:\car\multimidia\.tools\android-sdk\platform-tools\adb.exe'
if (-not $env:ADB_PATH -and (Test-Path $adbDefault)) {
  $env:ADB_PATH = $adbDefault
}

# Valida sintaxe de todos os módulos .mjs antes de rodar
foreach ($f in @('adb.mjs', 'cdp.mjs', 'run-audit.mjs')) {
  $full = Join-Path $auditDir $f
  & node --check $full
  if ($LASTEXITCODE -ne 0) {
    throw "Falha de sintaxe em $full"
  }
}

$nodeArgs = @(
  (Join-Path $auditDir 'run-audit.mjs'),
  '--out-dir', $OutDir,
  '--port', "$Port",
  '--timeout', "$TimeoutMs"
)

if ($DryRun)  { $nodeArgs += '--dry-run' }
if ($NoStart) { $nodeArgs += '--no-start' }
if ($Only)    { $nodeArgs += @('--only', $Only) }
if ($Serial)  { $nodeArgs += @('--serial', $Serial) }
if ($Install) {
  $nodeArgs += '--install'
  if ($ApkPath) { $nodeArgs += $ApkPath }
}

& node @nodeArgs
exit $LASTEXITCODE
