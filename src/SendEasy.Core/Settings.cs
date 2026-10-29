using System.Text.Json;

namespace SendEasy.Core;

public enum ReceiveMode { AskAlways, AutoFromPaired, AutoFromAll }

/// <summary>מכשיר מועדף. לכל מכשיר אפשר קיצור מקשים משלו ששולח אליו ישירות.</summary>
public sealed class FavoriteDevice
{
    public string Name { get; set; } = "";
    public ulong Address { get; set; }
    public string Hotkey { get; set; } = "";
}

public sealed class Settings
{
    public string ReceiveDir { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "שליחה קלה");
    /// <summary>לחלץ אוטומטית ארכיונים ששלחה התוכנה (נושאים סימון מזהה).</summary>
    public bool ExtractOwnArchives { get; set; } = true;
    /// <summary>לחלץ כל ZIP נכנס, גם אם לא נוצר על ידי התוכנה.</summary>
    public bool ExtractAllZips { get; set; } = false;
    public bool DeleteZipAfterExtract { get; set; } = true;
    /// <summary>ברירת מחדל: קבלה אוטומטית ממכשירים מצומדים, ושאלה על כל השאר.</summary>
    public ReceiveMode ReceiveMode { get; set; } = ReceiveMode.AutoFromPaired;
    public bool ReceiveEnabled { get; set; } = true;
    /// <summary>סגנון הצ'אט: 0 = ווטסאפ, 1 = גוגל צ'אט.</summary>
    public int ChatStyle { get; set; } = 0;
    /// <summary>קיצור מקשים גלובלי: שולח את הקבצים שנבחרו בסייר.</summary>
    public bool HotkeyEnabled { get; set; } = true;
    public string Hotkey { get; set; } = "Ctrl+Alt+B";
    /// <summary>רשימת מכשירים מועדפים ומכשיר ברירת מחדל (כתובת בלוטוס, 0 = אין).</summary>
    public List<FavoriteDevice> Favorites { get; set; } = new();
    public ulong DefaultDevice { get; set; }
    /// <summary>קיצור המקשים הראשי שולח מיד למכשיר ברירת המחדל, בלי חלון בחירה.</summary>
    public bool HotkeyDirect { get; set; } = false;
    /// <summary>קיצור לשליחת טקסט מהלוח (כקובץ טקסט) למכשיר ברירת המחדל.</summary>
    public bool ClipboardHotkeyEnabled { get; set; } = true;
    public string ClipboardHotkey { get; set; } = "Ctrl+Alt+V";
    /// <summary>בדיקת עדכונים: מאגר GitHub בפורמט owner/repo. ריק = כבוי.</summary>
    public string UpdateRepo { get; set; } = "YairDaniel11/send-easy-bt";
    public long MaxReceiveBytes { get; set; } = 4L * 1024 * 1024 * 1024;
    public long MaxExtractBytes { get; set; } = 8L * 1024 * 1024 * 1024;
    public int MaxExtractFiles { get; set; } = 20000;
    /// <summary>מזהה קבוע של המחשב (16 בייט, הקסה) בפרוטוקול הפרטי. נוצר בשימוש הראשון.</summary>
    public string DeviceId { get; set; } = "";

    public byte[] GetOrCreateDeviceId()
    {
        if (DeviceId.Length != 32)
        {
            DeviceId = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            try { Save(); } catch { }
        }
        return Convert.FromHexString(DeviceId);
    }

    static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "settings.json");

    public static Settings Load()
    {
        try { if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new(); }
        catch { }
        return new();
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
