<#
  Instala (ou atualiza) a versão COMPLETA do HondaDeck no tablet pelo USB (ADB).
  É o jeito padrão de instalar em casa. Detalhes em HondaDeck/INSTALACAO.md.

  Uso, dentro da pasta HondaDeck:
    .\scripts\instalar-usb.ps1              # compila no notebook e instala
    .\scripts\instalar-usb.ps1 -DoGitHub    # baixa o HondaDeck-completo.apk mais novo e instala
    .\scripts\instalar-usb.ps1 -AbrirNotificacoes   # depois de instalar, abre "Acesso a notificações" no tablet

  Atualizar mantém configurações, chave do Gemini e diário de abastecimento
  (mesmo pacote e mesma assinatura).
#>
param(
    [switch]$DoGitHub,
    [switch]$AbrirNotificacoes
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$package = 'com.autodeck.hondadeck'
$releaseUrl = 'https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/HondaDeck-completo.apk'

function Find-Adb {
    $candidates = @()
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') }
    }
    $lp = Join-Path $root 'local.properties'
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $dir = ($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':'
            $candidates += (Join-Path $dir 'platform-tools\adb.exe')
        }
    }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb não encontrado. Instale o Android SDK Platform-Tools (Android Studio > SDK Manager) ou coloque o adb no PATH."
}

$adb = Find-Adb
Write-Host "adb: $adb"

# Confere o tablet conectado e autorizado
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
if (-not $devices) {
    throw "Nenhum tablet encontrado. Ligue o cabo USB e ative a Depuração USB (veja INSTALACAO.md)."
}
if ($devices -match 'unauthorized') {
    throw "O tablet ainda não autorizou este computador. Desbloqueie a tela e toque em 'Permitir' no aviso de depuração USB."
}
Write-Host "Tablet: $($devices -join '; ')"

if ($DoGitHub) {
    $apk = Join-Path $env:TEMP 'HondaDeck-completo.apk'
    Write-Host "Baixando a versão mais nova do GitHub..."
    [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
    Invoke-WebRequest -UseBasicParsing $releaseUrl -OutFile $apk
    Write-Host "Instalando..."
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "A instalação falhou (código $LASTEXITCODE)." }
} else {
    # Mesmo ajuste de JDK do scripts/check.ps1
    $toolsRoot = Join-Path (Split-Path -Parent $root) '.tools'
    if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $toolsRoot 'jdk21\jdk-21.0.13.1+2'))) {
        $env:JAVA_HOME = Join-Path $toolsRoot 'jdk21\jdk-21.0.13.1+2'
    }
    Push-Location $root
    try {
        Write-Host "Compilando e instalando a versão completa (:app:installCompletoDebug)..."
        & .\gradlew.bat --console=plain :app:installCompletoDebug
        if ($LASTEXITCODE -ne 0) { throw "O build ou a instalação falhou (código $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
}

# Abre o app no tablet
& $adb shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null

if ($AbrirNotificacoes) {
    & $adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS | Out-Null
    Write-Host "Abri 'Acesso a notificações' no tablet: ative o HondaDeck." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "HondaDeck completo instalado." -ForegroundColor Green
Write-Host "Primeira vez? Ative o HondaDeck em Acesso a notificações (use -AbrirNotificacoes)."
