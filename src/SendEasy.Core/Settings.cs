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
    /// <summary>חלון קטן בפינה בכל הודעה חדשה (כשחלון הצ'אט לא פתוח בחזית).</summary>
    public bool NotifyPopup { get; set; } = true;
    /// <summary>צליל בעת קבלת הודעה.</summary>
    public bool NotifySound { get; set; } = true;

    public const string DefaultUpdateRepo = "YairDaniel11/send-easy-bt";
    public string UpdateRepo { get; set; } = DefaultUpdateRepo;
    public long MaxReceiveBytes { get; set; } = 4L * 1024 * 1024 * 1024;
    public long MaxExtractBytes { get; set; } = 8L * 1024 * 1024 * 1024;
    public int MaxExtractFiles { get; set; } = 20000;
    /// <summary>מזהה קבוע של המחשב (16 בייט, הקסה) בפרוטוקול הפרטי. נוצר בשימוש הראשון.</summary>
    public string DeviceId { get; set; } = "";

    /// <summary>כינוי שהמשתמש נתן למכשיר (מפתח: כתובת בלוטוס בהקסה). מוצג במקום השם המקורי בכל התוכנה.</summary>
    public Dictionary<string, string> Aliases { get; set; } = new();

    static string AliasKey(ulong address) => address.ToString("x12");

    /// <summary>השם להצגה: הכינוי אם יש, אחרת השם שהמכשיר מדווח.</summary>
    public string DisplayName(ulong address, string fallback) =>
        address != 0 && Aliases.TryGetValue(AliasKey(address), out var a) && !string.IsNullOrWhiteSpace(a) ? a : fallback;

    public string? Alias(ulong address) =>
        address != 0 && Aliases.TryGetValue(AliasKey(address), out var a) && !string.IsNullOrWhiteSpace(a) ? a : null;

    /// <summary>קובע כינוי. ריק = מחיקה (חזרה לשם המקורי). הכינוי מתעדכן גם במועדפים.</summary>
    public void SetAlias(ulong address, string? name)
    {
        if (address == 0) return;
        if (string.IsNullOrWhiteSpace(name)) Aliases.Remove(AliasKey(address)); else Aliases[AliasKey(address)] = name.Trim();
    }

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
        try
        {
            if (File.Exists(FilePath))
            {
                var s = JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new();
                if (string.IsNullOrWhiteSpace(s.UpdateRepo)) s.UpdateRepo = DefaultUpdateRepo; // שדה העדכונים הוסר מההגדרות
                return s;
            }
        }
        catch { }
        return new();
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
