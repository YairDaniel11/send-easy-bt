# בונה את המתקין לתיקיית release:
#   SendEasyBT-Setup.exe  מתקין יחיד: מזהה אם .NET 8 Desktop Runtime מותקן, ואם לא מוריד ומתקין אותו
# לא נוגע במערכת: Inno Setup נלקח מחבילת NuGet ולא מותקן.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$app = Join-Path $root 'src\SendEasy.App\SendEasy.App.csproj'
$version = ([xml](Get-Content $app -Encoding UTF8)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1

Write-Host '== full build + signed package'
& (Join-Path $PSScriptRoot 'package.ps1')
if ($LASTEXITCODE -ne 0) { throw 'package failed' }

Write-Host '== installers'
$iscc = Get-ChildItem "$env:USERPROFILE\.nuget\packages\tools.innosetup\*\tools\ISCC.exe" | Sort-Object FullName | Select-Object -Last 1
$iss = Join-Path $PSScriptRoot 'setup.iss'
& $iscc.FullName "/DAppVersion=$version" "/DSourceDir=$dist" "/DSuffix=" $iss | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'full setup failed' }
Get-ChildItem (Join-Path $root 'release') -Filter *.exe | ForEach-Object { '{0}  {1:N1} MB' -f $_.Name, ($_.Length / 1MB) }
