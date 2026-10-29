; מתקין "שליחה קלה - BT". נבנה על ידי build_release.ps1.
; משתנים: AppVersion, SourceDir, Suffix. התוכנה דורשת .NET 8 Desktop Runtime: המתקין מזהה אם הוא כבר מותקן ואם לא, מוריד ומתקין אותו.

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{B1E2F7C4-3D5A-4F7E-9A21-6C0D8E5B7A10}
AppName=שליחה קלה - BT
AppVersion={#AppVersion}
AppPublisher=שליחה קלה - BT
DefaultDirName={localappdata}\Programs\SendEasyBT
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\release
OutputBaseFilename=SendEasyBT-Setup{#Suffix}
Compression=lzma2/ultra64
SolidCompression=yes
SetupIconFile=..\src\SendEasy.App\Assets\app.ico
UninstallDisplayIcon={app}\SendEasyBT.exe
WizardStyle=modern
CloseApplications=force

[Languages]
Name: "he"; MessagesFile: "compiler:Languages\Hebrew.isl"

[Tasks]
Name: "modern"; Description: "הוסף פריט ברמה העליונה של הקליק הימני ב-Windows 11 (יבקש אישור מנהל להוספת תעודה)"; Check: IsWindows11

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion; Excludes: "*.pdb"

[Run]
; הרישום עצמו (קליק ימני, "שלח אל", הפעלה עם Windows) נעשה על ידי התוכנה
Filename: "{app}\SendEasyBT.exe"; Parameters: "--install --quiet --modern"; Tasks: modern; Flags: waituntilterminated
Filename: "{app}\SendEasyBT.exe"; Parameters: "--install --quiet"; Tasks: not modern; Flags: waituntilterminated
; מפעיל את התוכנה מחדש בסוף (גם בעדכון שקט מתוך התוכנה)
Filename: "{app}\SendEasyBT.exe"; Parameters: "--background"; Flags: nowait runasoriginaluser

[UninstallRun]
Filename: "{app}\SendEasyBT.exe"; Parameters: "--uninstall --quiet"; RunOnceId: "SendEasyUninstall"; Flags: waituntilterminated skipifdoesntexist

[Code]
function IsWindows11: Boolean;
var V: TWindowsVersion;
begin
  GetWindowsVersionEx(V);
  Result := V.Build >= 22000;
end;

var
  DownloadPage: TDownloadWizardPage;

function HasDesktopRuntime: Boolean;
var Names: TArrayOfString; I: Integer;
begin
  Result := False;
  if RegGetValueNames(HKLM64, 'SOFTWARE\dotnet\Setup\InstalledVersions\x64\sharedfx\Microsoft.WindowsDesktop.App', Names) then
    for I := 0 to GetArrayLength(Names) - 1 do
      if Copy(Names[I], 1, 2) = '8.' then Result := True;
end;

procedure InitializeWizard;
begin
  DownloadPage := CreateDownloadPage(SetupMessage(msgWizardPreparing), SetupMessage(msgPreparingDesc), nil);
end;

{ אם .NET 8 Desktop Runtime כבר מותקן, מדלגים. אחרת מורידים ומתקינים אותו (יבקש אישור מנהל) }
function NextButtonClick(CurPageID: Integer): Boolean;
var Code: Integer;
begin
  Result := True;
  if (CurPageID = wpReady) and not HasDesktopRuntime then
  begin
    DownloadPage.Clear;
    DownloadPage.Add('https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe', 'windowsdesktop-runtime.exe', '');
    DownloadPage.Show;
    try
      try
        DownloadPage.Download;
      except
        Result := False;
        MsgBox('הורדת רכיב .NET 8 נכשלה: ' + GetExceptionMessage + #13#10 + 'בדוק חיבור לאינטרנט ונסה שוב.', mbError, MB_OK);
      end;
    finally
      DownloadPage.Hide;
    end;
    if Result then
    begin
      if not ShellExec('runas', ExpandConstant('{tmp}\windowsdesktop-runtime.exe'), '/install /quiet /norestart', '', SW_SHOW, ewWaitUntilTerminated, Code)
         or ((Code <> 0) and (Code <> 3010)) then
      begin
        Result := False;
        MsgBox('התקנת רכיב .NET 8 לא הושלמה (קוד ' + IntToStr(Code) + ').', mbError, MB_OK);
      end;
    end;
  end;
end;