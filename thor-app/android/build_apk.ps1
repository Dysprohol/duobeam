# Builds the DuoBeam app for the AYN Thor (release, unminified) and optionally installs it.
#   .\build_apk.ps1                          - build only
#   .\build_apk.ps1 -Install                 - build + adb install (the only connected device)
#   .\build_apk.ps1 -Install -Serial <id>    - build + adb install on a chosen device
# Needs a JDK 17+ (JAVA_HOME) and the Android SDK (ANDROID_HOME or sdk.dir in local.properties).
param([switch]$Install, [string]$Serial = '')
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
& .\gradlew.bat --no-daemon assembleRelease 2>&1 | Tee-Object -FilePath build_apk.log
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED (log: build_apk.log)"; exit 1 }
$apk = 'app\build\outputs\apk\release\app-release.apk'
Write-Host "APK: $((Get-Item $apk).Length) B"
if ($Install) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { $adb = 'adb' }
    if ($Serial) { & $adb -s $Serial install -r $apk } else { & $adb install -r $apk }
}
Write-Host "BUILD DONE"
