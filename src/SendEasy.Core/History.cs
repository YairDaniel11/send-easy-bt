using System.Text.Json;

namespace SendEasy.Core;

public sealed class HistoryEntry
{
    public DateTime Time { get; set; } = DateTime.Now;
    public bool Sent { get; set; }
    public string Device { get; set; } = "";
    public string Name { get; set; } = "";
    public long Size { get; set; }
    /// <summary>נתיב הקובץ או התיקייה (בקבלה: היעד, בשליחה: המקור).</summary>
    public string? Path { get; set; }
    public bool Ok { get; set; } = true;
    public string? Note { get; set; }
}

/// <summary>היסטוריית שליחה וקבלה, נשמרת מקומית בלבד (שורת JSON לכל רשומה).</summary>
public static class History
{
    const int Max = 500;
    static readonly object Gate = new();

    static string FilePath => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "history.jsonl");

    public static void Add(HistoryEntry e)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(FilePath)!);
                File.AppendAllText(FilePath, JsonSerializer.Serialize(e) + "\n");
            }
        }
        catch { }
    }

    public static List<HistoryEntry> Load()
    {
        var list = new List<HistoryEntry>();
        try
        {
            lock (Gate)
            {
                if (!File.Exists(FilePath)) return list;
                foreach (var line in File.ReadLines(FilePath))
                    try { var e = JsonSerializer.Deserialize<HistoryEntry>(line); if (e != null) list.Add(e); } catch { }
                if (list.Count > Max)
                {
                    list = list.Skip(list.Count - Max).ToList();
                    File.WriteAllLines(FilePath, list.Select(x => JsonSerializer.Serialize(x)));
                }
            }
        }
        catch { }
        list.Reverse(); // החדש ביותר ראשון
        return list;
    }

    public static void Clear()
    {
        try { lock (Gate) { if (File.Exists(FilePath)) File.Delete(FilePath); } } catch { }
    }
}
