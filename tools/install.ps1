# Dynamic Edge: build, install and grant everything over adb.
# Usage: .\tools\install.ps1 [-NoBuild]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$pkg = 'com.dynedge'
$svc = 'com.dynedge/com.dynedge.EdgeService'
$listener = 'com.dynedge/com.dynedge.MediaListener'
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'

if (-not $NoBuild) {
    Write-Host 'Building release APK...'
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Build failed.'
        exit 1
    }
}

if (-not (Test-Path $apk)) {
    Write-Host ('APK not found: ' + $apk)
    exit 1
}

Write-Host 'Installing...'
& adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host 'adb install failed.'
    exit 1
}

# Read the current accessibility service list (colon separated).
$cur = (& adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($cur -eq 'null') { $cur = '' }

$items = @()
if ($cur) {
    $items = @($cur.Split(':') | Where-Object { $_ -and ($_ -ne $svc) })
}
$without = $items -join ':'
$with = (@($items) + $svc) -join ':'

# Toggle off then on so a reinstalled service is restarted.
& adb shell "settings put secure enabled_accessibility_services '$without'"
Start-Sleep -Milliseconds 800
& adb shell "settings put secure enabled_accessibility_services '$with'"
& adb shell settings put secure accessibility_enabled 1

& adb shell cmd notification allow_listener $listener
& adb shell cmd notification allow_dnd $pkg
& adb shell appops set $pkg WRITE_SETTINGS allow
# Recent apps ribbon in the APPS window (UsageStatsManager).
& adb shell appops set $pkg GET_USAGE_STATS allow
# Lets the service keep the corner-swipe Google Assistant gesture switched off (it collides with the widget).
& adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
& adb shell settings put secure navigation_google_assist 0
# Real audio levels for the idle music visualizer (falls back to a synthetic animation without it).
& adb shell pm grant $pkg android.permission.RECORD_AUDIO
# Keep the overlay alive: exempt from Doze/battery optimisation (vivo's com.vivo.abe otherwise force-stops it).
& adb shell dumpsys deviceidle whitelist +$pkg
& adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow

$final = (& adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
Write-Host ''
Write-Host 'Done.'
Write-Host ('  APK:            ' + $apk)
Write-Host ('  Accessibility:  ' + $final)
Write-Host '  Media access, Do Not Disturb access and Modify system settings granted.'
