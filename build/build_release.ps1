# בונה את המתקין לתיקיית release:
#   SendEasyBT-Setup-<גרסה>.exe  מתקין יחיד: מזהה אם .NET 8 Desktop Runtime מותקן, ואם לא מוריד ומתקין אותו
# לא נוגע במערכת: Inno Setup נלקח מחבילת NuGet ולא מותקן.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$app = Join-Path $root 'src\SendEasy.App\SendEasy.App.csproj'
$version = ([xml](Get-Content $app -Encoding UTF8)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1

Write-Host '== full build + signed package'
& (Join-Path $PSScriptRoot 'package.ps1')
if ($LASTEXITCODE -ne 0) { throw 'package failed' }

Write-Host '== android app (bundled for "send update to phone")'
# האפליקציה נכנסת לתוכנה בשם SendEasyBT-<גרסה>.apk, כדי שאפשר יהיה לשלוח אותה לטלפון בבלוטוס בלי אינטרנט
Get-ChildItem $dist -Filter 'SendEasyBT-*.apk' -ErrorAction SilentlyContinue | Remove-Item -Force
$apk = Get-ChildItem (Join-Path $root 'android\app\build\outputs\apk\release') -Filter '*.apk' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '(\d+\.\d+\.\d+)\.apk$' } | Sort-Object LastWriteTime | Select-Object -Last 1
if ($apk) {
    $apkVersion = [regex]::Match($apk.Name, '(\d+\.\d+\.\d+)\.apk$').Groups[1].Value
    Copy-Item $apk.FullName (Join-Path $dist "SendEasyBT-$apkVersion.apk") -Force
    Write-Host "bundled $($apk.Name) as SendEasyBT-$apkVersion.apk"
} else { Write-Host 'no release APK found under androidppuild\outputspkelease: the installer will not include the app' }

Write-Host '== installers'
$iscc = Get-ChildItem "$env:USERPROFILE\.nuget\packages\tools.innosetup\*\tools\ISCC.exe" | Sort-Object FullName | Select-Object -Last 1
$iss = Join-Path $PSScriptRoot 'setup.iss'
& $iscc.FullName "/DAppVersion=$version" "/DSourceDir=$dist" "/DSuffix=-$version" $iss | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'full setup failed' }
Get-ChildItem (Join-Path $root 'release') -Filter *.exe | ForEach-Object { '{0}  {1:N1} MB' -f $_.Name, ($_.Length / 1MB) }
