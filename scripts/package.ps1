param([ValidateSet('debug','release')][string]$AndroidBuildType='debug',[switch]$DevelopmentSigned)
$ErrorActionPreference='Stop'
$Root=Split-Path $PSScriptRoot -Parent
$Dist=Join-Path $Root 'dist'
$Desktop=Join-Path $Root 'desktop/build/compose/binaries/main/app/Futbolio'
$Apk=Join-Path $Root "android/build/outputs/apk/$AndroidBuildType/android-$AndroidBuildType.apk"
if(!(Test-Path "$Desktop/Futbolio.exe") -or !(Test-Path $Apk)){throw 'Build the Windows desktop distribution and Android APK first.'}
New-Item -ItemType Directory -Force $Dist | Out-Null
Compress-Archive -Path $Desktop -DestinationPath "$Dist/Futbolio-0.3.0-windows.zip" -Force
$Label=if($DevelopmentSigned -or $AndroidBuildType -eq 'debug'){'development'}else{'release'}
Copy-Item $Apk "$Dist/Futbolio-0.3.0-tv-$Label.apk" -Force
Copy-Item "$Root/docs/MANUAL-TESTS.md" "$Dist/MANUAL-TESTS.md" -Force
Get-ChildItem $Dist -File | Where-Object {$_.Extension -in @('.apk','.zip')} | ForEach-Object {
    $Hash=Get-FileHash $_.FullName -Algorithm SHA256
    "$($Hash.Hash.ToLowerInvariant())  $($_.Name)"
} | Set-Content -Encoding ASCII "$Dist/SHA256SUMS"
Get-ChildItem $Dist -File | Select-Object Name,Length
