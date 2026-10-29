namespace SendEasy.Core;

/// <summary>חיבורים יוצאים פתוחים אל אפליקציית הטלפון, לשימוש חוזר בצ'אט (חיבור אחד לכל מכשיר).</summary>
public static class PeerConnections
{
    static readonly Dictionary<ulong, PeerSession> Sessions = new();
    static readonly SemaphoreSlim Gate = new(1, 1);

    public static bool IsConnected(ulong address)
    {
        lock (Sessions) return Sessions.TryGetValue(address, out var s) && !s.IsClosed;
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
            lock (Sessions) Sessions[address] = s;
            s.Closed += _ => { lock (Sessions) if (Sessions.TryGetValue(address, out var cur) && ReferenceEquals(cur, s)) Sessions.Remove(address); };
            return s;
        }
        finally { Gate.Release(); }
    }

    public static void CloseAll()
    {
        lock (Sessions) { foreach (var s in Sessions.Values.ToArray()) s.Close(); Sessions.Clear(); }
    }
}