param(
    [string[]]$Tasks = @(":core:test", ":app:assembleDebug", ":app:lintDebug"),
    [string]$SdkPath = "",
    [string]$GradleBin = ""
)
$ErrorActionPreference = "Stop"
if (-not $SdkPath) {
    $SdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
}
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath -PathType Container)) {
    throw "Set ANDROID_HOME / ANDROID_SDK_ROOT, or pass -SdkPath pointing to an Android SDK."
}
$sourceRoot = $PSScriptRoot
$taskRoot = Split-Path -Parent $sourceRoot
$taskWork = Join-Path $taskRoot "work"
New-Item -ItemType Directory -Path $taskWork,(Join-Path $taskWork "tmp"),(Join-Path $taskWork "gradle-home"),(Join-Path $taskWork "signing") -Force | Out-Null
$env:GRADLE_USER_HOME = Join-Path $taskWork "gradle-home"
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath
$env:ANDROID_USER_HOME = Join-Path $taskWork "android-user"
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$env:TEMP = Join-Path $taskWork "tmp"
$env:TMP = $env:TEMP
$socketFallback = Join-Path $taskWork "no-unix-sockets"
if (Test-Path -LiteralPath $socketFallback) { throw "The JDK socket fallback path must not exist." }
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$(Join-Path $taskWork 'tmp') -Djdk.net.unixdomain.tmpdir=$socketFallback"
$sdkProperty = $SdkPath.Replace('\', '\\').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $sourceRoot "local.properties") -Value "sdk.dir=$sdkProperty" -Encoding ascii
$debugKey = Join-Path $taskWork "signing\demo-debug.keystore"
if (-not (Test-Path -LiteralPath $debugKey)) {
    & keytool -genkeypair -keystore $debugKey -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "Debug key generation failed" }
}
if (-not $GradleBin) {
    $localGradle = Join-Path $taskWork "toolchain\gradle-8.14.3\bin\gradle.bat"
    $GradleBin = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $sourceRoot "gradlew.bat" }
}
Push-Location $taskWork
try {
    & $GradleBin -p $sourceRoot --project-cache-dir (Join-Path $taskWork "project-cache") --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
