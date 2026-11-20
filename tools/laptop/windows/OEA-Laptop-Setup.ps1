<#
.SYNOPSIS
  Installs OEA, the Android launcher, into an Android emulator on Windows.

.DESCRIPTION
  OEA is an Android app, so it runs on Windows through Android. This script
  prepares the Android emulator, starts it, installs the release OEA APK and
  sets OEA as the Home app. Run OEA-Laptop-Setup.cmd by double-clicking it; this
  file can also be run directly with -Apk to install a specific APK.

.PARAMETER Apk
  Path to OEA-laptop-<abi>.apk. If omitted, the script looks next to itself and
  in the repository's dist folder, then downloads the latest release.

.PARAMETER AvdName
  Emulator name to create or reuse. Default: oea-laptop.

.PARAMETER Api
  Android API level for the system image. Default: 36.
#>
[CmdletBinding()]
param(
    [string]$Apk = "",
    [string]$AvdName = "oea-laptop",
    [string]$Api = "36"
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Failure([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
}

function Get-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }
    $candidates += "C:\Android\Sdk"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }
    return $null
}

function Get-SdkTool([string]$Sdk, [string]$RelativePath) {
    $path = Join-Path $Sdk $RelativePath
    if (Test-Path $path) { return $path }
    return $null
}

function Get-SdkManager([string]$Sdk) {
    $direct = Get-SdkTool $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
    if ($direct) { return $direct }
    $direct = Get-SdkTool $Sdk "cmdline-tools\bin\sdkmanager.bat"
    if ($direct) { return $direct }
    $root = Join-Path $Sdk "cmdline-tools"
    if (Test-Path $root) {
        $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "bin\sdkmanager.bat" } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($found) { return $found }
    }
    return $null
}

function Get-AvdManager([string]$Sdk) {
    $sdkManager = Get-SdkManager $Sdk
    if (-not $sdkManager) { return $null }
    $avdManager = Join-Path (Split-Path $sdkManager -Parent) "avdmanager.bat"
    if (Test-Path $avdManager) { return $avdManager }
    return $null
}

function Invoke-Adb([string]$Adb, [string[]]$Arguments) {
    $output = & $Adb @Arguments 2>$null
    return ($output | Out-String).Trim()
}

Write-Host "OEA for Windows" -ForegroundColor Green
Write-Host "Android launcher setup - installs the release APK and sets OEA as Home."

$sdk = Get-AndroidSdk
if (-not $sdk) {
    Write-Failure "The Android SDK was not found."
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($winget) {
        $answer = Read-Host "Android Studio is not installed. Install it now with winget? [y/N]"
        if ($answer -match "^(y|yes)$") {
            Write-Step "Installing Android Studio with winget (large download)"
            & winget install --id Google.AndroidStudio -e --accept-package-agreements --accept-source-agreements
            $sdk = Get-AndroidSdk
        }
    }
    if (-not $sdk) {
        Write-Host ""
        Write-Host "Install Android Studio, then open it once so it finishes setting up:"
        Write-Host "  winget install --id Google.AndroidStudio -e"
        Write-Host "Then run this setup again."
        exit 1
    }
}

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "Android SDK: $sdk"

$sdkManager = Get-SdkManager $sdk
if (-not $sdkManager) {
    Write-Failure "Android SDK command-line tools are missing."
    Write-Host "Open Android Studio > Settings > Languages & Frameworks > Android SDK > SDK Tools,"
    Write-Host "tick 'Android SDK Command-line Tools (latest)', apply, then run this setup again."
    exit 1
}

$avdManager = Get-AvdManager $sdk
$adb = Get-SdkTool $sdk "platform-tools\adb.exe"
$emulator = Get-SdkTool $sdk "emulator\emulator.exe"

$abi = "x86_64"
if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { $abi = "arm64-v8a" }
$image = "system-images;android-$Api;google_apis;$abi"

Write-Step "Installing the Android emulator, platform tools and the API $Api image ($abi)"
Write-Host "First run downloads a few hundred MB."
$licenseAnswers = 1..50 | ForEach-Object { "y" }
$licenseAnswers | & $sdkManager --licenses | Out-Null
& $sdkManager "platform-tools" "emulator" "platforms;android-$Api" $image
if ($LASTEXITCODE -ne 0) {
    Write-Failure "The Android SDK packages could not be installed."
    exit 1
}

if (-not $adb) { $adb = Get-SdkTool $sdk "platform-tools\adb.exe" }
if (-not $emulator) { $emulator = Get-SdkTool $sdk "emulator\emulator.exe" }
if (-not $adb -or -not $emulator) {
    Write-Failure "adb or the emulator is still missing after installation."
    exit 1
}

$avds = & $emulator -list-avds
if ($avds -notcontains $AvdName) {
    Write-Step "Creating the $AvdName emulator"
    "no" | & $avdManager create avd -n $AvdName -k $image -d pixel_tablet --force
    if ($LASTEXITCODE -ne 0) {
        "no" | & $avdManager create avd -n $AvdName -k $image --force
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Failure "The emulator could not be created."
        exit 1
    }
}

$devices = Invoke-Adb $adb @("devices")
$hasDevice = $devices -match "\sdevice$"
if (-not $hasDevice) {
    Write-Step "Starting $AvdName"
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $AvdName, "-gpu", "auto", "-no-snapshot")
}

Write-Step "Waiting for Android to boot"
$booted = $false
$deadline = (Get-Date).AddMinutes(8)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if ((Invoke-Adb $adb @("shell", "getprop", "sys.boot_completed")) -eq "1") {
        $booted = $true
        break
    }
}
if (-not $booted) {
    Write-Failure "Android did not finish booting."
    exit 1
}

if (-not $Apk) {
    $candidates = @(
        (Join-Path $PSScriptRoot "OEA-laptop-$abi.apk"),
        (Join-Path $PSScriptRoot "..\..\dist\OEA-laptop-$abi.apk")
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Apk -or -not (Test-Path $Apk)) {
    $url = "https://github.com/Emmanuel001afk/OEA/releases/download/oea-laptop-latest/OEA-laptop-$abi.apk"
    $Apk = Join-Path $env:TEMP "OEA-laptop-$abi.apk"
    Write-Step "Downloading OEA from GitHub"
    try {
        Invoke-WebRequest -Uri $url -OutFile $Apk -UseBasicParsing
    } catch {
        Write-Failure "OEA could not be downloaded from $url"
        exit 1
    }
}

Write-Step "Installing OEA"
& $adb install -r $Apk
if ($LASTEXITCODE -ne 0) {
    Write-Failure "OEA could not be installed."
    Write-Host "If the message says INSTALL_FAILED_UPDATE_INCOMPATIBLE, an older OEA with a"
    Write-Host "different signature is installed. Remove it and run this setup again:"
    Write-Host "  `"$adb`" uninstall com.oea.launcher"
    exit 1
}

Write-Step "Setting OEA as the Home app"
& $adb shell cmd package set-home-activity com.oea.launcher/.OeaLauncherActivity | Out-Null
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null

Write-Host ""
Write-Host "OEA is installed and set as Home." -ForegroundColor Green
Write-Host "If Android asks which Home app to use, choose OEA and 'Always'."
Write-Host "Mouse clicks are taps; the keyboard types."
exit 0
