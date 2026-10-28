# בונה גרסת הפצה: publish + חבילה דלילה (MSIX) חתומה בחתימה עצמית לפריט בתפריט הקליק הימני של Windows 11.
# לא נוגע במערכת: המפתח הפרטי נשמר בקובץ בתיקיית keys (לא נכנס ל-git) ולא במאגר התעודות של Windows.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$keys = Join-Path $root 'keys'
$work = Join-Path $root 'build\pkg'
$subject = 'CN=SendEasyBT'

Write-Host '== publish'
dotnet publish (Join-Path $root 'src\SendEasy.App\SendEasy.App.csproj') -c Release -r win-x64 --self-contained false -o $dist | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'publish failed' }

Write-Host '== certificate'
New-Item -ItemType Directory -Force $keys | Out-Null
$pfx = Join-Path $keys 'SendEasyBT.pfx'
$pwFile = Join-Path $keys 'pfx-password.txt'
$cer = Join-Path $keys 'SendEasyBT.cer'
if (-not (Test-Path $pfx)) {
    $rsa = [System.Security.Cryptography.RSA]::Create(3072)
    $req = New-Object System.Security.Cryptography.X509Certificates.CertificateRequest($subject, $rsa,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $req.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension($false, $false, 0, $true)))
    $req.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509KeyUsageExtension([System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature, $true)))
    $eku = New-Object System.Security.Cryptography.OidCollection
    [void]$eku.Add((New-Object System.Security.Cryptography.Oid('1.3.6.1.5.5.7.3.3')))
    $req.CertificateExtensions.Add((New-Object System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension($eku, $true)))
    $cert = $req.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddYears(10))
    $pw = [Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Maximum 256 }))
    [IO.File]::WriteAllBytes($pfx, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, $pw))
    [IO.File]::WriteAllBytes($cer, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
    Set-Content $pwFile $pw -NoNewline
    Write-Host 'created new signing key in keys\ (keep it private, do NOT commit)'
}
$pw = (Get-Content $pwFile -Raw).Trim()
if (-not (Test-Path $cer)) {
    $c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($pfx, $pw)
    [IO.File]::WriteAllBytes($cer, $c.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
}

Write-Host '== package'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $work 'Assets') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'logo44.png') (Join-Path $work 'Assets\logo44.png')
Copy-Item (Join-Path $PSScriptRoot 'logo150.png') (Join-Path $work 'Assets\logo150.png')
Copy-Item (Join-Path $PSScriptRoot 'AppxManifest.xml') (Join-Path $work 'AppxManifest.xml')

$tools = Get-ChildItem "$env:USERPROFILE\.nuget\packages\microsoft.windows.sdk.buildtools\*\bin\*\x64" -Directory | Sort-Object FullName | Select-Object -Last 1
$msix = Join-Path $dist 'SendEasyBT.msix'
Remove-Item $msix -ErrorAction SilentlyContinue
& (Join-Path $tools.FullName 'makeappx.exe') pack /o /nv /d $work /p $msix | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'makeappx failed' }
& (Join-Path $tools.FullName 'signtool.exe') sign /fd SHA256 /f $pfx /p $pw $msix | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'signtool failed' }
Copy-Item $cer (Join-Path $dist 'SendEasyBT.cer') -Force
Write-Host "done: $msix"

