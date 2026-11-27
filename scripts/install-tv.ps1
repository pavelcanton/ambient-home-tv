# install-tv.ps1 — quick ADB installer for Ambient Home on Android TV / Google TV.
# Connects to a parameterized TV device IP[:port], installs AmbientHome.apk, runs AOT speed compile,
# and optionally grants NotificationListener ("Now Playing") and sets Ambient Home as default HOME.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Device,

    [string]$ApkPath = "",

    [switch]$NowPlaying,

    [switch]$SetHome,

    [switch]$SkipAot
)

$ErrorActionPreference = 'Stop'

$trimmed = $Device.Trim()
if ($trimmed -notmatch '^(?:[0-9]{1,3}(?:\.[0-9]{1,3}){3}|[a-zA-Z0-9][a-zA-Z0-9.-]{0,252})(?::[0-9]{1,5})?$') {
    throw "Invalid -Device format '$Device'. Expected <ip> or <ip:port> (e.g. 192.168.1.50:5555)."
}

$hostPart = $trimmed
$portPart = 5555
if ($trimmed -match '^(.+):([0-9]+)$') {
    $hostPart = $Matches[1]
    $portPart = [int]$Matches[2]
    if ($portPart -lt 1 -or $portPart -gt 65535) {
        throw "Invalid port in -Device: $portPart (must be 1..65535)"
    }
}
if ($hostPart -match '^([0-9]+)\.([0-9]+)\.([0-9]+)\.([0-9]+)$') {
    foreach ($oct in @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])) {
        if ($oct -lt 0 -or $oct -gt 255) {
            throw "Invalid IPv4 octet in -Device: $hostPart"
        }
    }
}
$target = "${hostPart}:${portPart}"

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $launcherRoot = Split-Path -Parent $PSScriptRoot
    $ApkPath = Join-Path $launcherRoot 'AmbientHome.apk'
}
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath (build with .\build.ps1 or download from GitHub Releases)"
}

$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCmd) {
    throw "adb not found in PATH. Install Android Platform-Tools first."
}

function Invoke-Adb([string[]]$AdbArgs) {
    & $adbCmd.Source @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($AdbArgs -join ' ') failed (rc=$LASTEXITCODE)"
    }
}

Write-Host "== 1. Connecting to $target =="
Invoke-Adb @('connect', $target)

Write-Host "== 2. Installing $(Split-Path -Leaf $ApkPath) =="
Invoke-Adb @('-s', $target, 'install', '-r', $ApkPath)

if (-not $SkipAot) {
    Write-Host "== 3. Running AOT speed compilation (com.will.ambienthome) =="
    Invoke-Adb @('-s', $target, 'shell', 'cmd', 'package', 'compile', '-m', 'speed', '-f', 'com.will.ambienthome')
} else {
    Write-Host "== 3. Skipping AOT compilation (-SkipAot) =="
}

if ($NowPlaying) {
    Write-Host "== 4. Granting NotificationListener (Now Playing pill) =="
    Invoke-Adb @('-s', $target, 'shell', 'cmd', 'notification', 'allow_listener', 'com.will.ambienthome/com.will.ambienthome.data.AmbientNotificationListener')
}

if ($SetHome) {
    Write-Host "== 5. Setting Ambient Home as default HOME launcher =="
    Invoke-Adb @('-s', $target, 'shell', 'cmd', 'package', 'set-home-activity', 'com.will.ambienthome/.MainActivity')
}

Write-Host ''
Write-Host "INSTALL COMPLETE on $target" -ForegroundColor Green
Write-Host "  LAN Web Remote URL: http://${hostPart}:8765"
Write-Host "  (Find the 6-digit PIN in Ambient Home -> Quick Controls or Settings -> LAN Web Remote)"
