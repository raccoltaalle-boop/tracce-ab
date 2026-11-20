# Tracce A-B : compila l'APK su Windows.
# Al primo avvio scarica JDK 17, Android SDK e Gradle in %LOCALAPPDATA%\TracceAB-build (circa 1,5 GB, 10-20 minuti).
# Le volte successive impiega 1-2 minuti.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root  = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path $env:LOCALAPPDATA 'TracceAB-build'
$log   = Join-Path $root 'build.log'
Set-Content -Path $log -Value ("Build avviata " + (Get-Date)) -Encoding UTF8
New-Item -ItemType Directory -Force $tools | Out-Null

function Log($m, $c = 'Gray') { Write-Host $m -ForegroundColor $c; Add-Content -Path $log -Value $m -Encoding UTF8 }
function Step($m) { Log ""; Log "=== $m ===" 'Cyan' }

# Esegue un programma mostrando e salvando l'output; restituisce il codice di uscita
function Run($exe, [string[]]$argv) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $exe @argv 2>&1 | ForEach-Object { $s = "$_"; Write-Host $s; Add-Content -Path $log -Value $s -Encoding UTF8 }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    return $code
}

function Get-Zip($url, $dest) {
    if (Test-Path $dest) { Log "Gia' presente: $dest"; return }
    $zip = "$dest.zip"
    Log "Scarico $url"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    New-Item -ItemType Directory -Force $dest | Out-Null
    Log "Estraggo..."
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($tar) { & tar.exe -xf $zip -C $dest } else { Expand-Archive -Path $zip -DestinationPath $dest -Force }
    Remove-Item $zip -Force
}

$ok = $false
try {
    Step 'JDK 17'
    $jdkDir = Join-Path $tools 'jdk17'
    Get-Zip 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $jdkDir
    $javaHome = (Get-ChildItem $jdkDir -Directory | Select-Object -First 1).FullName
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    Log "JAVA_HOME = $javaHome"

    Step 'Android SDK'
    $sdk  = Join-Path $tools 'sdk'
    $cmdl = Join-Path $sdk 'cmdline-tools\latest'
    if (-not (Test-Path "$cmdl\bin\sdkmanager.bat")) {
        $tmp = Join-Path $tools 'cmdtmp'
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Get-Zip 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $tmp
        New-Item -ItemType Directory -Force (Join-Path $sdk 'cmdline-tools') | Out-Null
        if (Test-Path $cmdl) { Remove-Item $cmdl -Recurse -Force }
        Move-Item (Join-Path $tmp 'cmdline-tools') $cmdl
        Remove-Item $tmp -Recurse -Force
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $sdkm = "$cmdl\bin\sdkmanager.bat"
    if (-not (Test-Path "$sdk\platforms\android-34\android.jar") -or -not (Test-Path "$sdk\build-tools\34.0.0")) {
        Log 'Accetto le licenze Android SDK...'
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        ("y`n" * 40) | & $sdkm "--sdk_root=$sdk" --licenses 2>&1 | Out-Null
        $ErrorActionPreference = $old
        Log 'Installo piattaforma 34 e build-tools (qualche minuto)...'
        $pk = Join-Path $tools 'packages.txt'
        Set-Content -Path $pk -Value @('platforms;android-34', 'build-tools;34.0.0', 'platform-tools') -Encoding ASCII
        $c = Run $sdkm @("--sdk_root=$sdk", "--package_file=$pk")
        if (-not (Test-Path "$sdk\platforms\android-34\android.jar")) { throw "Installazione SDK non riuscita (codice $c)" }
    } else { Log 'SDK gia'' installato' }

    Step 'Gradle'
    $gDir = Join-Path $tools 'gradle'
    Get-Zip 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $gDir
    $gradle = Join-Path $gDir 'gradle-8.7\bin\gradle.bat'

    Step 'Compilazione APK'
    Set-Content -Path (Join-Path $root 'local.properties') -Value ("sdk.dir=" + $sdk.Replace('\', '/')) -Encoding ASCII
    Push-Location $root
    $c = Run $gradle @('--no-daemon', '--console=plain', 'assembleDebug')
    Pop-Location
    if ($c -ne 0) { throw "Gradle ha restituito l'errore $c (dettagli in build.log)" }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    $out = Join-Path $root 'TracceAB.apk'
    Copy-Item $apk $out -Force
    Log ""
    Log "APK PRONTO: $out" 'Green'
    $ok = $true

    Step 'Installazione sul telefono'
    $adb = "$sdk\platform-tools\adb.exe"
    $ErrorActionPreference = 'Continue'
    $devs = (& $adb devices 2>&1) | ForEach-Object { "$_" } | Select-String "`tdevice$"
    if ($devs) {
        Log 'Telefono collegato via USB: installo...'
        Run $adb @('install', '-r', $out) | Out-Null
    } else {
        Log 'Nessun telefono collegato con debug USB.'
        Log 'Copia TracceAB.apk sul telefono (cavo, Drive, Telegram...) e aprilo per installarlo.'
    }
}
catch {
    Log ""
    Log ("ERRORE: " + $_.Exception.Message) 'Red'
    Log "Il dettaglio completo e' nel file build.log nella cartella del progetto." 'Red'
}
if ($ok) { exit 0 } else { exit 1 }
