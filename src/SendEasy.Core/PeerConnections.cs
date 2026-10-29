namespace SendEasy.Core;

/// <summary>
/// חיבורים פתוחים אל אפליקציית הטלפון. שני סוגים:
/// יוצאים (אנחנו פתחנו): מתאימים לשליחת הודעות, קבצים והקלטות, כי האפליקציה בטלפון מקבלת עליהם תוכן;
/// נכנסים (הטלפון פתח כדי לשלוח לנו): בצד הטלפון הם חיבור שליחה בלבד ולכן מסרבים לקבל קבצים,
/// ומשמשים רק לאישורי קריאה.
/// </summary>
public static class PeerConnections
{
    static readonly Dictionary<ulong, PeerSession> Outbound = new();
    static readonly Dictionary<ulong, PeerSession> Inbound = new();
    static readonly SemaphoreSlim Gate = new(1, 1);

    /// <summary>הצד השני קרא הודעה שלנו: (כתובת המכשיר, מזהה ההודעה).</summary>
    public static event Action<ulong, long>? ReadReceived;
    /// <summary>הטלפון פתח צ'אט חדש איתנו.</summary>
    public static event Action<ulong>? NewChatReceived;

    static bool Alive(Dictionary<ulong, PeerSession> d, ulong address, out PeerSession? s)
    {
        lock (d) return d.TryGetValue(address, out s) && !s.IsClosed;
    }

    public static bool IsConnected(ulong address) => Alive(Outbound, address, out _) || Alive(Inbound, address, out _);

    /// <summary>רושם חיבור כחיבור הפעיל של המכשיר (inbound = הטלפון פתח אותו).</summary>
    public static void Register(ulong address, PeerSession s, bool inbound)
    {
        if (address == 0) return;
        var map = inbound ? Inbound : Outbound;
        s.RemoteAddress = address;
        s.ReadReceived += id => ReadReceived?.Invoke(address, id);
        s.NewChatReceived += () => NewChatReceived?.Invoke(address);
        lock (map) map[address] = s;
        s.Closed += _ => { lock (map) if (map.TryGetValue(address, out var cur) && ReferenceEquals(cur, s)) map.Remove(address); };
    }

    /// <summary>
    /// מחזיר חיבור יוצא קיים או פותח חדש (לשליחת תוכן). null אם האפליקציה לא מותקנת או לא פתוחה בטלפון.
    /// לא משתמש בחיבורים נכנסים, כי הטלפון לא מקבל עליהם קבצים.
    /// </summary>
    public static async Task<PeerSession?> GetAsync(ulong address, CancellationToken ct = default)
    {
        await Gate.WaitAsync(ct);
        try
        {
            if (Alive(Outbound, address, out var existing)) return existing;
            var s = await PeerClient.TryConnectAsync(address, ct);
            if (s == null) return null;
            Register(address, s, inbound: false);
            return s;
        }
        finally { Gate.Release(); }
    }

    /// <summary>שולח אישורי קריאה להודעות של המכשיר, על כל חיבור פתוח (ואם אין, פותח חיבור יוצא). true אם נשלחו.</summary>
    public static async Task<bool> SendReadAsync(ulong address, IEnumerable<long> msgIds)
    {
        try
        {
            PeerSession? s = null;
            if (!Alive(Inbound, address, out s) && !Alive(Outbound, address, out s)) s = await GetAsync(address);
            if (s == null) return false;
            foreach (var id in msgIds) await s.SendReadAsync(id);
            return true;
        }
        catch { return false; }
    }

    /// <summary>מודיע לטלפון שנפתח צ'אט חדש (אם מחובר או ניתן להתחבר).</summary>
    public static async Task SendNewChatAsync(ulong address)
    {
        try
        {
            PeerSession? s = null;
            if (!Alive(Inbound, address, out s) && !Alive(Outbound, address, out s)) s = await GetAsync(address);
            if (s != null) await s.SendNewChatAsync();
        }
        catch { }
    }

    public static void CloseAll()
    {
        foreach (var map in new[] { Outbound, Inbound })
            lock (map) { foreach (var s in map.Values.ToArray()) s.Close(); map.Clear(); }
    }
}