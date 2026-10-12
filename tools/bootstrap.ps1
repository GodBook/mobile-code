param([string]$SdkRoot = $env:ANDROID_SDK_ROOT)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path (Join-Path $root '.downloads') | Out-Null
$wrapper = Join-Path $root 'gradle/wrapper/gradle-wrapper.jar'
if (!(Test-Path -LiteralPath $wrapper)) {
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar' -OutFile $wrapper
}
if ($SdkRoot) {
    Write-Host "SDK: $SdkRoot. Install platform 37, NDK 28.2.13676358, and CMake 3.22.1 using Android SDK Manager."
}
Write-Host 'Next: run tools/prepare_toolchains.py inside Linux/WSL, then ./gradlew :app:assembleRelease.'
