using System.Text.Json;

namespace SendEasy.Core;

/// <summary>הודעה בהיסטוריית הצ'אט. נשמרת בקובץ שורות לכל מכשיר (לא בהגדרות).</summary>
public sealed class ChatEntry
{
    public DateTime Time { get; set; } = DateTime.Now;
    public bool Mine { get; set; }
    /// <summary>"text" או "file"</summary>
    public string Kind { get; set; } = "text";
    public string Text { get; set; } = "";
    public long Size { get; set; }
    /// <summary>"sent" / "delivered" / "failed"</summary>
    public string Status { get; set; } = "sent";
    public string? Path { get; set; }
    /// <summary>שם המכשיר מהצד השני (לתצוגת רשימת השיחות)</summary>
    public string Device { get; set; } = "";
}

/// <summary>היסטוריית צ'אט לכל מכשיר: %APPDATA%\SendEasyBT\chat\{כתובת}.jsonl, עד 1000 הודעות לשיחה.</summary>
public static class ChatLog
{
    const int MaxKeep = 1000;
    static readonly object Gate = new();
    static readonly JsonSerializerOptions Json = new() { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    static string Dir => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "chat");

    /// <summary>הודעות קוליות (מקבלים ושולחים), לא בתיקיית ההורדות.</summary>
    public static string VoiceDir => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "voice");

    public static bool IsVoiceName(string name) => name.StartsWith("voice-", StringComparison.Ordinal) && name.EndsWith(".m4a", StringComparison.OrdinalIgnoreCase);

    /// <summary>משך ההקלטה בשניות, מתוך השם (voice-...-d12.m4a).</summary>
    public static int VoiceSeconds(string name)
    {
        var m = System.Text.RegularExpressions.Regex.Match(name, @"-d(\d+)\.m4a$");
        return m.Success && int.TryParse(m.Groups[1].Value, out var s) ? s : 0;
    }

    static string FileOf(ulong address) => System.IO.Path.Combine(Dir, address.ToString("x12") + ".jsonl");

    public static void Add(ulong address, ChatEntry e)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Dir);
                var f = FileOf(address);
                File.AppendAllText(f, JsonSerializer.Serialize(e, Json) + "\n", new System.Text.UTF8Encoding(false));
                if (new FileInfo(f).Length > 1024 * 1024)
                {
                    var all = Load(address, int.MaxValue);
                    File.WriteAllLines(f, all.Skip(Math.Max(0, all.Count - MaxKeep)).Select(x => JsonSerializer.Serialize(x, Json)),
                        new System.Text.UTF8Encoding(false));
                }
            }
        }
        catch { }
    }

    public static List<ChatEntry> Load(ulong address, int max = 500)
    {
        var list = new List<ChatEntry>();
        try
        {
            lock (Gate)
            {
                var f = FileOf(address);
                if (!File.Exists(f)) return list;
                foreach (var line in File.ReadLines(f, System.Text.Encoding.UTF8))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    try { var e = JsonSerializer.Deserialize<ChatEntry>(line); if (e != null) list.Add(e); } catch { }
                }
            }
        }
        catch { }
        return list.Count > max ? list.GetRange(list.Count - max, max) : list;
    }

    /// <summary>כל השיחות שיש להן הודעות: כתובת, שם אחרון שידוע, וזמן ההודעה האחרונה.</summary>
    public static List<(ulong Address, string Name, DateTime Last)> Conversations()
    {
        var res = new List<(ulong, string, DateTime)>();
        try
        {
            if (!Directory.Exists(Dir)) return res;
            foreach (var f in Directory.GetFiles(Dir, "*.jsonl"))
            {
                if (!ulong.TryParse(System.IO.Path.GetFileNameWithoutExtension(f), System.Globalization.NumberStyles.HexNumber, null, out var addr)) continue;
                var msgs = Load(addr, 50);
                if (msgs.Count == 0) continue;
                var name = msgs.AsEnumerable().Reverse().Select(m => m.Device).FirstOrDefault(d => !string.IsNullOrWhiteSpace(d)) ?? "";
                res.Add((addr, name, msgs[^1].Time));
            }
        }
        catch { }
        return res;
    }
}