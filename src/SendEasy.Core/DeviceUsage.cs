using System.Text.Json;

namespace SendEasy.Core;

/// <summary>כמה משתמשים בכל מכשיר (שליחת קובץ או הודעה). משמש למיון הרשימות: הנפוצים והאחרונים קודם.</summary>
public static class DeviceUsage
{
    /// <summary>חצי חיים של ניקוד: שימוש בן 30 יום שווה חצי משימוש של היום.</summary>
    const double HalfLifeDays = 30;
    static readonly object Gate = new();

    sealed class Stat { public double Score { get; set; } public DateTime Last { get; set; } }

    static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "usage.json");

    static double Decayed(Stat s, DateTime now) => s.Score * Math.Pow(0.5, Math.Max(0, (now - s.Last).TotalDays) / HalfLifeDays);

    static Dictionary<string, Stat> Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Dictionary<string, Stat>>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { }
        return new();
    }

    /// <summary>רושם שימוש אחד במכשיר (קובץ או הודעה שנשלחו בהצלחה).</summary>
    public static void Record(ulong address)
    {
        if (address == 0) return;
        try
        {
            lock (Gate)
            {
                var all = Load();
                var key = address.ToString("x12");
                var now = DateTime.UtcNow;
                all[key] = new Stat { Score = (all.TryGetValue(key, out var s) ? Decayed(s, now) : 0) + 1, Last = now };
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(all));
            }
        }
        catch { }
    }

    /// <summary>הניקוד הנוכחי של כל המכשירים (גבוה = בשימוש תדיר ולאחרונה). מכשיר שלא נמצא = 0.</summary>
    public static Func<ulong, double> Scores()
    {
        Dictionary<string, Stat> all;
        lock (Gate) all = Load();
        var now = DateTime.UtcNow;
        return a => all.TryGetValue(a.ToString("x12"), out var s) ? Decayed(s, now) : 0;
    }
}
