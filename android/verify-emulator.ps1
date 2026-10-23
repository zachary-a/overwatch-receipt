param(
    [string]$Serial = "emulator-5580",
    [string]$AdbPath = "",
    [switch]$ResetDemoData
)
$ErrorActionPreference = "Stop"
if (-not $AdbPath) {
    $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    $AdbPath = if ($taskSdk) { Join-Path $taskSdk "platform-tools/adb.exe" } else { "adb" }
}
if (-not $Serial.StartsWith("emulator-")) {
    throw "Automated UI verification is restricted to an Android emulator. Physical phone UI is user-operated."
}
$taskWork = Join-Path (Split-Path -Parent $PSScriptRoot) "work"
$appApk = Join-Path $taskWork "build\app\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $taskWork "build\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
New-Item -ItemType Directory -Path (Join-Path $taskWork "screenshots") -Force | Out-Null
Push-Location $taskWork
try {
    & $AdbPath -s $Serial install -r $appApk
    if ($LASTEXITCODE -ne 0) { throw "App installation failed" }
    & $AdbPath -s $Serial install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw "Test installation failed" }
    if ($ResetDemoData) {
        & $AdbPath -s $Serial shell pm clear dev.owreceipt.app.demo
        if ($LASTEXITCODE -ne 0) { throw "Demo data reset failed" }
    }
    $testLog = & $AdbPath -s $Serial shell am instrument -w -r dev.owreceipt.app.demo.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $testLog | Tee-Object -FilePath (Join-Path $taskWork "instrumentation.log")
    & $AdbPath -s $Serial pull /sdcard/Android/data/dev.owreceipt.app.demo/files/verification/. (Join-Path $taskWork "screenshots")
    $testText = $testLog -join [Environment]::NewLine
    if ($testText -notmatch "OK \(\d+ tests?\)" -or $testText -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed") {
        throw "Instrumentation failed. See work/instrumentation.log."
    }
} finally { Pop-Location }
