# Deploy & Launch Wear OS LSL Streamer to Galaxy Watch 7 via Wireless ADB
param (
    [string]$WatchIP = "192.168.137.102",  # Replace with Galaxy Watch IP
    [string]$WatchPort = "46267"           # Wireless Debugging Port
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Error "ADB not found at $adb."
    exit 1
}

Write-Host "[+] Connecting to Galaxy Watch 7 at ${WatchIP}:${WatchPort}..." -ForegroundColor Cyan
$connectResult = & $adb connect "${WatchIP}:${WatchPort}"
Write-Host $connectResult

Write-Host "[+] Installing app to watch..." -ForegroundColor Cyan
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "[+] Launching LSL Wear Streamer app on Watch screen..." -ForegroundColor Cyan
& $adb shell am start -n com.nautilus.watchstreamer/.MainActivity

Write-Host "[+] App launched successfully on Galaxy Watch 7!" -ForegroundColor Green
