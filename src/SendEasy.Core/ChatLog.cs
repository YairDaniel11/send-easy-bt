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
    /// <summary>מזהה ההודעה בפרוטוקול (0 = אין), לאישורי קריאה.</summary>
    public long MsgId { get; set; }
    /// <summary>"sent" / "delivered" / "read" / "failed"</summary>
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

    /// <summary>תמונות שהודבקו מהלוח לצ'אט (נשמרות כדי שיוצגו גם אחרי השליחה).</summary>
    public static string ImageDir => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SendEasyBT", "images");

    public static bool IsVoiceName(string name) => name.StartsWith("voice-", StringComparison.Ordinal) && name.EndsWith(".m4a", StringComparison.OrdinalIgnoreCase);

    /// <summary>משך ההקלטה בשניות, מתוך השם (voice-...-d12.m4a).</summary>
    public static int VoiceSeconds(string name)
    {
        var m = System.Text.RegularExpressions.Regex.Match(name, @"-d(\d+)\.m4a$");
        return m.Success && int.TryParse(m.Groups[1].Value, out var s) ? s : 0;
    }

    static string ThreadFile(ulong address, int n) =>
        System.IO.Path.Combine(Dir, address.ToString("x12") + (n <= 1 ? "" : "." + n) + ".jsonl");

    static string ActiveFile(ulong address) => System.IO.Path.Combine(Dir, address.ToString("x12") + ".active");

    /// <summary>מספר השיחה הפעילה עם המכשיר (1 = הקובץ המקורי). אליה נכנסות הודעות חדשות והיא מוצגת בצ'אט.</summary>
    public static int ActiveThread(ulong address)
    {
        try { return int.TryParse(File.ReadAllText(ActiveFile(address)).Trim(), out var n) && n >= 1 ? n : 1; }
        catch { return 1; }
    }

    public static void SetActiveThread(ulong address, int n)
    {
        try { lock (Gate) { Directory.CreateDirectory(Dir); File.WriteAllText(ActiveFile(address), n.ToString()); } }
        catch { }
    }

    static string FileOf(ulong address) => ThreadFile(address, ActiveThread(address));

    /// <summary>מספרי כל השיחות הקיימות עם המכשיר (כולל הפעילה), מהחדשה לישנה.</summary>
    public static List<int> Threads(ulong address)
    {
        var res = new List<int>();
        try
        {
            if (Directory.Exists(Dir))
                foreach (var f in Directory.GetFiles(Dir, address.ToString("x12") + "*.jsonl"))
                {
                    var mid = System.IO.Path.GetFileNameWithoutExtension(f)[12..];
                    if (mid.Length == 0) res.Add(1);
                    else if (mid.StartsWith('.') && int.TryParse(mid[1..], out var n)) res.Add(n);
                }
        }
        catch { }
        var act = ActiveThread(address);
        if (!res.Contains(act)) res.Add(act);
        res.Sort((a, b) => b.CompareTo(a));
        return res;
    }

    public static List<ChatEntry> LoadThread(ulong address, int n)
    {
        var list = new List<ChatEntry>();
        try
        {
            lock (Gate)
            {
                var f = ThreadFile(address, n);
                if (!File.Exists(f)) return list;
                foreach (var line in File.ReadLines(f, System.Text.Encoding.UTF8))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    try { var e = JsonSerializer.Deserialize<ChatEntry>(line); if (e != null) list.Add(e); } catch { }
                }
            }
        }
        catch { }
        return list;
    }

    /// <summary>פותח שיחה חדשה עם המכשיר והופך אותה לפעילה. אם הפעילה עדיין ריקה נשארים בה (false).</summary>
    public static bool NewThread(ulong address)
    {
        lock (Gate)
        {
            var f = FileOf(address);
            if (!File.Exists(f) || new FileInfo(f).Length == 0) return false;
            SetActiveThread(address, Threads(address).Max() + 1);
            return true;
        }
    }

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

    /// <summary>מסמן הודעה (לפי מזהה) כנקראה. מחזיר true אם נמצאה והשתנתה.</summary>
    public static bool MarkRead(ulong address, long msgId)
    {
        if (msgId == 0) return false;
        try
        {
            lock (Gate)
            {
                var f = FileOf(address);
                if (!File.Exists(f)) return false;
                var all = Load(address, int.MaxValue);
                bool changed = false;
                foreach (var e in all)
                    if (e.MsgId == msgId && e.Status != "read") { e.Status = "read"; changed = true; }
                if (changed) File.WriteAllLines(f, all.Select(x => JsonSerializer.Serialize(x, Json)), new System.Text.UTF8Encoding(false));
                return changed;
            }
        }
        catch { return false; }
    }

    /// <summary>מוחק שיחה: את היסטוריית ההודעות ואת קובצי ההקלטות שלה (קבצים שהתקבלו בתיקיית הקבלה לא נמחקים).</summary>
    public static void Delete(ulong address)
    {
        try
        {
            lock (Gate)
            {
                foreach (var e in Load(address, int.MaxValue))
                    if (e.Kind == "voice" && !string.IsNullOrEmpty(e.Path) && e.Path.StartsWith(VoiceDir, StringComparison.OrdinalIgnoreCase))
                        try { File.Delete(e.Path); } catch { }
                var f = FileOf(address);
                if (File.Exists(f)) File.Delete(f);
                var act = ActiveThread(address);
                var prev = Threads(address).FirstOrDefault(n => n != act && File.Exists(ThreadFile(address, n)));
                SetActiveThread(address, prev == 0 ? 1 : prev);
            }
        }
        catch { }
    }

    /// <summary>הודעות נכנסות שעוד לא אישרנו קריאה שלהן.</summary>
    public static List<ChatEntry> Unread(ulong address) =>
        Load(address).Where(e => !e.Mine && e.MsgId != 0 && e.Status != "read" && e.Kind == "text").ToList();

    /// <summary>כל השיחות שיש להן הודעות: כתובת, שם אחרון שידוע, וזמן ההודעה האחרונה.</summary>
    public static List<(ulong Address, string Name, DateTime Last)> Conversations()
    {
        var res = new List<(ulong, string, DateTime)>();
        try
        {
            if (!Directory.Exists(Dir)) return res;
            var addrs = new HashSet<ulong>();
            foreach (var f in Directory.GetFiles(Dir, "*.jsonl"))
            {
                var nm = System.IO.Path.GetFileNameWithoutExtension(f);
                if (nm.Length >= 12 && ulong.TryParse(nm[..12], System.Globalization.NumberStyles.HexNumber, null, out var a0)) addrs.Add(a0);
            }
            foreach (var addr in addrs)
            {
                var msgs = Load(addr, 50);
                var named = msgs.Count > 0 ? msgs : Threads(addr).Select(n => LoadThread(addr, n)).FirstOrDefault(l => l.Count > 0) ?? msgs;
                if (named.Count == 0) continue;
                var name = named.AsEnumerable().Reverse().Select(m => m.Device).FirstOrDefault(d => !string.IsNullOrWhiteSpace(d)) ?? "";
                res.Add((addr, name, msgs.Count > 0 ? msgs[^1].Time : named[^1].Time));
            }
        }
        catch { }
        return res;
    }
}