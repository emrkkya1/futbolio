param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
$Root=Split-Path $PSScriptRoot -Parent
if(!$env:JAVA_HOME){$env:JAVA_HOME='C:\emreky\android-tools\jdk'}
if(!$env:ANDROID_HOME){$env:ANDROID_HOME='C:\emreky\android-tools\sdk'}
$Adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
Push-Location $Root
try {
    & .\gradlew.bat :android:assembleDebug :android:assembleDebugAndroidTest --console=plain
    if($LASTEXITCODE -ne 0){throw 'Android test build failed'}
    & $Adb -s $Serial wait-for-device
    if($LASTEXITCODE -ne 0){throw 'Device unavailable'}
    & $Adb -s $Serial shell am force-stop app.futbolio.android
    & $Adb -s $Serial install -r android/build/outputs/apk/debug/android-debug.apk
    if($LASTEXITCODE -ne 0){throw 'App installation failed'}
    & $Adb -s $Serial install -r android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
    if($LASTEXITCODE -ne 0){throw 'Test installation failed'}
    $Result=& $Adb -s $Serial shell am instrument -w app.futbolio.android.test/app.futbolio.android.SmokeInstrumentation
    New-Item -ItemType Directory -Force test-results | Out-Null
    $Result | Tee-Object test-results/android-smoke.txt
    if(($Result | Out-String) -notmatch 'FUTBOLIO ANDROID CHECKS PASSED'){throw 'Android smoke checks failed'}
} finally {Pop-Location}
