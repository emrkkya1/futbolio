param([switch]$Release)
$ErrorActionPreference='Stop'
$Root=Split-Path $PSScriptRoot -Parent
if(!$env:JAVA_HOME){$env:JAVA_HOME='C:\emreky\android-tools\jdk'}
if(!$env:ANDROID_HOME){$env:ANDROID_HOME='C:\emreky\android-tools\sdk'}
Push-Location $Root
try {
    $AndroidTask=if($Release){':android:assembleRelease'}else{':android:assembleDebug'}
    & .\gradlew.bat :domain:test :application:test :engine:check :desktop:test :desktop:createDistributable $AndroidTask --console=plain
    if($LASTEXITCODE -ne 0){throw 'Futbolio build/tests failed'}
} finally {Pop-Location}
