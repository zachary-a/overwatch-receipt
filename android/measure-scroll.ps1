param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = ""
)
$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    $AdbPath = if ($taskSdk) { Join-Path $taskSdk "platform-tools/adb.exe" } else { "adb" }
}
if (-not $Serial.StartsWith('emulator-')) { throw 'Use the project emulator, not a physical phone.' }
$taskWork = Join-Path (Split-Path -Parent $PSScriptRoot) 'work'
$taskResults = Join-Path $taskWork 'scroll-comparison'
New-Item -ItemType Directory -Path $taskResults -Force | Out-Null
$env:ANDROID_USER_HOME = Join-Path $taskWork 'android-user'
function Invoke-Adb([string[]]$Arguments) {
    $taskText = & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Arguments" }
    return $taskText
}
function Measure-Scroll([string]$Package, [string]$Name) {
    # Both packages contain only synthetic demo data. Start from identical preferences and content.
    Invoke-Adb -Arguments @('shell','pm','clear',$Package) | Out-Null
    Invoke-Adb -Arguments @('shell','am','start','-W','-n',"$Package/dev.owreceipt.app.MainActivity") | Out-Null
    Start-Sleep -Milliseconds 1500
    Invoke-Adb -Arguments @('shell','input','swipe','540','1700','540','450','800') | Out-Null
    Invoke-Adb -Arguments @('shell','input','swipe','540','450','540','1700','800') | Out-Null
    $taskRuns = foreach ($taskIteration in 1..3) {
        Invoke-Adb -Arguments @('shell','dumpsys','gfxinfo',$Package,'reset') | Out-Null
        Invoke-Adb -Arguments @('shell','input','swipe','540','1700','540','450','1000') | Out-Null
        Invoke-Adb -Arguments @('shell','input','swipe','540','450','540','1700','1000') | Out-Null
        $taskStats = Invoke-Adb -Arguments @('shell','dumpsys','gfxinfo',$Package,'framestats')
        $taskStats | Set-Content -LiteralPath (Join-Path $taskResults "$Name-$taskIteration.txt")
        $taskJoined = $taskStats -join "`n"
        $taskFrames = [int][regex]::Match($taskJoined, 'Total frames rendered: (\d+)').Groups[1].Value
        if ($taskFrames -lt 10) { throw "Not enough scroll frames: $Package / $taskFrames" }
        [pscustomobject]@{
            frames = $taskFrames
            p50ms = [int][regex]::Match($taskJoined, '50th percentile: (\d+)ms').Groups[1].Value
            p90ms = [int][regex]::Match($taskJoined, '90th percentile: (\d+)ms').Groups[1].Value
        }
    }
    Invoke-Adb -Arguments @('shell','am','force-stop',$Package) | Out-Null
    [pscustomobject]@{
        package = $Package; runs = @($taskRuns)
        medianP50ms = @($taskRuns.p50ms | Sort-Object)[1]
        medianP90ms = @($taskRuns.p90ms | Sort-Object)[1]
    }
}
Push-Location $taskWork
try {
    $taskDebug = Measure-Scroll 'dev.owreceipt.app.demo' 'debug'
    $taskOptimized = Measure-Scroll 'dev.owreceipt.app.benchmark' 'optimized'
    $taskReport = [ordered]@{
        device = $Serial; syntheticDemoData = $true
        debug = $taskDebug; optimized = $taskOptimized
        p90ReductionPercent = [Math]::Round(100 * (1 - $taskOptimized.medianP90ms / [double]$taskDebug.medianP90ms), 1)
        limitation = '60 Hz software-rendered emulator; not a physical-phone high-refresh measurement.'
    }
    $taskReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskResults 'result.json')
    $taskReport | ConvertTo-Json -Depth 6
} finally { Pop-Location }
