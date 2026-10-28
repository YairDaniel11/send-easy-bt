namespace SendEasy.Core;

public static class FileNames
{
    static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    public static bool IsReservedOrInvalidSegment(string seg)
    {
        if (seg.Length == 0 || seg == "." || seg == "..") return true;
        if (seg.EndsWith('.') || seg.EndsWith(' ')) return true;
        if (seg.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return true;
        var stem = seg.Split('.')[0].TrimEnd(' ');
        return Reserved.Contains(stem);
    }

    /// <summary>שם קובץ בטוח ליצירה בדיסק, מתוך שם שהגיע מהרשת.</summary>
    public static string Sanitize(string raw)
    {
        var name = Path.GetFileName(raw.Replace('\\', '/').Split('/').Last());
        foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
        name = name.TrimEnd('.', ' ');
        if (name.Length == 0) name = "file";
        if (IsReservedOrInvalidSegment(name)) name = "_" + name;
        if (name.Length > 150) name = name[..150];
        return name;
    }

    /// <summary>נתיב פנוי בתיקייה: אם השם קיים מוסיפים " (2)" וכו'.</summary>
    public static string UniquePath(string dir, string name)
    {
        var path = Path.Combine(dir, name);
        if (!File.Exists(path) && !Directory.Exists(path)) return path;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (int i = 2; ; i++)
        {
            path = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(path) && !Directory.Exists(path)) return path;
        }
    }
}
