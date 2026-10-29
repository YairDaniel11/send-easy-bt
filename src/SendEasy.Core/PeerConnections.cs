namespace SendEasy.Core;

/// <summary>
/// חיבורים פתוחים אל אפליקציית הטלפון, בשני הכיוונים (יוצאים שפתחנו, ונכנסים שהטלפון פתח). חיבור אחד לכל מכשיר,
/// לשימוש חוזר בצ'אט ובאישורי קריאה.
/// </summary>
public static class PeerConnections
{
    static readonly Dictionary<ulong, PeerSession> Sessions = new();
    static readonly SemaphoreSlim Gate = new(1, 1);

    /// <summary>הצד השני קרא הודעה שלנו: (כתובת המכשיר, מזהה ההודעה).</summary>
    public static event Action<ulong, long>? ReadReceived;

    public static bool IsConnected(ulong address)
    {
        lock (Sessions) return Sessions.TryGetValue(address, out var s) && !s.IsClosed;
    }

    /// <summary>רושם חיבור (נכנס או יוצא) כחיבור הפעיל של המכשיר.</summary>
    public static void Register(ulong address, PeerSession s)
    {
        if (address == 0) return;
        s.RemoteAddress = address;
        s.ReadReceived += id => ReadReceived?.Invoke(address, id);
        lock (Sessions) Sessions[address] = s;
        s.Closed += _ => { lock (Sessions) if (Sessions.TryGetValue(address, out var cur) && ReferenceEquals(cur, s)) Sessions.Remove(address); };
    }

    /// <summary>מחזיר חיבור קיים או פותח חדש. null אם האפליקציה לא מותקנת או לא פתוחה בטלפון.</summary>
    public static async Task<PeerSession?> GetAsync(ulong address, CancellationToken ct = default)
    {
        await Gate.WaitAsync(ct);
        try
        {
            lock (Sessions)
                if (Sessions.TryGetValue(address, out var existing) && !existing.IsClosed) return existing;
            var s = await PeerClient.TryConnectAsync(address, ct);
            if (s == null) return null;
            Register(address, s);
            return s;
        }
        finally { Gate.Release(); }
    }

    /// <summary>שולח אישורי קריאה להודעות של המכשיר. מחזיר true אם נשלחו.</summary>
    public static async Task<bool> SendReadAsync(ulong address, IEnumerable<long> msgIds)
    {
        try
        {
            var s = await GetAsync(address);
            if (s == null) return false;
            foreach (var id in msgIds) await s.SendReadAsync(id);
            return true;
        }
        catch { return false; }
    }

    public static void CloseAll()
    {
        lock (Sessions) { foreach (var s in Sessions.Values.ToArray()) s.Close(); Sessions.Clear(); }
    }
}