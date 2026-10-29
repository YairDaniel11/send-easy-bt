using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

namespace SendEasy.App;

/// <summary>התקנה והסרה למשתמש הנוכחי בלבד (HKCU), בלי הרשאות מנהל.</summary>
static class Installer
{
    public const string AppName = "שליחה קלה - BT";
    const string KeyName = "SendEasyBT";
    const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\SendEasyBT";
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static string InstallDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", KeyName);

    static string SendToLink => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.SendTo), AppName + ".lnk");

    public static string InstalledExe => Path.Combine(InstallDir, "SendEasyBT.exe");

    /// <summary>מעתיק את קבצי התוכנה לתיקיית ההתקנה ורושם את הפריטים במערכת.</summary>
    public static void Install(bool modern = false)
    {
        var src = AppContext.BaseDirectory.TrimEnd('\\');
        if (!string.Equals(src, InstallDir, StringComparison.OrdinalIgnoreCase))
            CopyDir(src, InstallDir);
        var exe = InstalledExe;

        // קליק ימני: קבצים ותיקיות (בתפריט הקלאסי; ב-Windows 11 תחת "הצג אפשרויות נוספות")
        foreach (var root in new[] { @"Software\Classes\*\shell\" + KeyName, @"Software\Classes\Directory\shell\" + KeyName })
        {
            using var k = Registry.CurrentUser.CreateSubKey(root);
            k.SetValue("MUIVerb", "שליחה קלה - BT (בלוטוס)");
            k.SetValue("Icon", $"\"{exe}\",0");
            k.SetValue("MultiSelectModel", "Player");
            using var c = k.CreateSubKey("command");
            c.SetValue("", $"\"{exe}\" \"%1\"");
        }

        // Windows 11: פריט ברמה העליונה של התפריט (חבילה חתומה בחתימה עצמית). אם הצליח, הפריט הקלאסי מיותר.
        if (!modern) { }
        else if (TryInstallModernMenu())
            foreach (var root in new[] { @"Software\Classes\*\shell\" + KeyName, @"Software\Classes\Directory\shell\" + KeyName })
                Registry.CurrentUser.DeleteSubKeyTree(root, false);

        // "שלח אל"
        try { CreateShortcut(SendToLink, exe); } catch { }

        // הפעלה עם Windows, כדי שהשרת יהיה זמין לקבלה
        using (var run = Registry.CurrentUser.CreateSubKey(RunKey))
            run.SetValue(KeyName, $"\"{exe}\" --background");

        // רישום ב"אפליקציות מותקנות" (אם המתקין כבר רשם, לא כופלים)
        using var innoKey = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\{B1E2F7C4-3D5A-4F7E-9A21-6C0D8E5B7A10}_is1");
        if (innoKey == null)
        using (var u = Registry.CurrentUser.CreateSubKey(UninstallKey))
        {
            u.SetValue("DisplayName", AppName);
            u.SetValue("DisplayVersion", "0.1.0");
            u.SetValue("Publisher", "קוד פתוח");
            u.SetValue("DisplayIcon", exe);
            u.SetValue("InstallLocation", InstallDir);
            u.SetValue("UninstallString", $"\"{exe}\" --uninstall");
            u.SetValue("NoModify", 1);
            u.SetValue("NoRepair", 1);
        }
    }

    public static void Uninstall()
    {
        RemoveModernMenu();
        foreach (var root in new[] { @"Software\Classes\*\shell\" + KeyName, @"Software\Classes\Directory\shell\" + KeyName })
            Registry.CurrentUser.DeleteSubKeyTree(root, false);
        Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false);
        using (var run = Registry.CurrentUser.OpenSubKey(RunKey, true)) run?.DeleteValue(KeyName, false);
        try { if (File.Exists(SendToLink)) File.Delete(SendToLink); } catch { }

        // מחיקת הקבצים אחרי שהתהליך הזה יוצא (אי אפשר למחוק קובץ שרץ)
        if (Directory.Exists(InstallDir))
        {
            var cmd = $"/c ping 127.0.0.1 -n 3 >nul & rmdir /s /q \"{InstallDir}\"";
            Process.Start(new ProcessStartInfo("cmd.exe", cmd) { CreateNoWindow = true, UseShellExecute = false });
        }
    }

    const string PackageName = "SendEasyBT.ContextMenu";
    static string MsixPath => Path.Combine(InstallDir, "SendEasyBT.msix");
    static string CerPath => Path.Combine(InstallDir, "SendEasyBT.cer");

    static int RunPowerShell(string script, bool elevated)
    {
        var psi = new ProcessStartInfo("powershell.exe")
        {
            Arguments = "-NoProfile -ExecutionPolicy Bypass -Command \"" + script.Replace("\"", "\\\"") + "\"",
            UseShellExecute = elevated, // הרמת הרשאות דורשת ShellExecute
            Verb = elevated ? "runas" : "",
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        using var p = Process.Start(psi)!;
        p.WaitForExit(120_000);
        return p.HasExited ? p.ExitCode : -1;
    }

    /// <summary>רושם חבילה דלילה חתומה בחתימה עצמית. הצעד היחיד שדורש מנהל: הוספת התעודה לרשימת "אנשים מהימנים".</summary>
    public static bool TryInstallModernMenu()
    {
        try
        {
            if (Environment.OSVersion.Version.Build < 22000) return false; // רק Windows 11 צריך את זה
            if (!File.Exists(MsixPath) || !File.Exists(CerPath)) return false;
            if (!IsCertTrusted() && RunPowerShell($"Import-Certificate -FilePath '{CerPath}' -CertStoreLocation Cert:\\LocalMachine\\TrustedPeople | Out-Null", elevated: true) != 0)
                return false;
            return RunPowerShell($"Add-AppxPackage -Path '{MsixPath}' -ExternalLocation '{InstallDir}'", elevated: false) == 0;
        }
        catch { return false; }
    }

    /// <summary>האם התעודה של התוכנה כבר מהימנה במחשב (ואז אין צורך בהרשאת מנהל בעדכון).</summary>
    public static bool IsCertTrusted()
    {
        try
        {
            if (!File.Exists(CerPath)) return false;
            using var cert = new System.Security.Cryptography.X509Certificates.X509Certificate2(CerPath);
            using var store = new System.Security.Cryptography.X509Certificates.X509Store(System.Security.Cryptography.X509Certificates.StoreName.TrustedPeople, System.Security.Cryptography.X509Certificates.StoreLocation.LocalMachine);
            store.Open(System.Security.Cryptography.X509Certificates.OpenFlags.ReadOnly);
            return store.Certificates.Find(System.Security.Cryptography.X509Certificates.X509FindType.FindByThumbprint, cert.Thumbprint, false).Count > 0;
        }
        catch { return false; }
    }

    /// <summary>האם הפריט ברמה העליונה כבר הותקן בעבר (התעודה מהימנה ו-Windows 11).</summary>
    public static bool ModernMenuWasInstalled => Environment.OSVersion.Version.Build >= 22000 && IsCertTrusted();

    public static void RemoveModernMenu()
    {
        try
        {
            RunPowerShell($"Get-AppxPackage -Name {PackageName} | Remove-AppxPackage", elevated: false);
            if (File.Exists(CerPath))
                RunPowerShell("$t=(New-Object Security.Cryptography.X509Certificates.X509Certificate2 '" + CerPath + "').Thumbprint; Remove-Item Cert:\\LocalMachine\\TrustedPeople\\$t -ErrorAction SilentlyContinue", elevated: true);
        }
        catch { }
    }

    public static bool IsInstalled() => File.Exists(InstalledExe);

    static void CopyDir(string src, string dst)
    {
        Directory.CreateDirectory(dst);
        foreach (var f in Directory.GetFiles(src))
            File.Copy(f, Path.Combine(dst, Path.GetFileName(f)), true);
        foreach (var d in Directory.GetDirectories(src))
            CopyDir(d, Path.Combine(dst, Path.GetFileName(d)));
    }

    static void CreateShortcut(string path, string target)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell")!;
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic lnk = shell.CreateShortcut(path);
        lnk.TargetPath = target;
        lnk.IconLocation = target + ",0";
        lnk.Save();
    }
}
