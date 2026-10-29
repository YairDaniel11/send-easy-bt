using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;

namespace SendEasy.Core;

/// <summary>
/// שרת הפרוטוקול הפרטי: מאזין ל-UUID של האפליקציה בטלפון (RFCOMM) ומקבל קבצים והודעות.
/// מקבל רק ממכשירים מצומדים. משתמש באותם אירועים וב-Decide כמו ObexServer.
/// </summary>
public sealed class PeerServer : IDisposable
{
    readonly Func<Settings> _settings;
    RfcommServiceProvider? _provider;
    StreamSocketListener? _listener;
    readonly List<PeerSession> _sessions = new();

    public Func<IncomingFile, Task<bool>> Decide { get; set; } = _ => Task.FromResult(true);
    public event Action<IncomingFile, long>? Progress;
    public event Action<IncomingFile, string>? Received;
    /// <summary>כמו Received, עם כתובת המכשיר השולח (לצ'אט).</summary>
    public event Action<ulong, IncomingFile, string>? ReceivedFrom;
    public event Action<IncomingFile>? Aborted;
    /// <summary>הודעת טקסט שהתקבלה: (שם המכשיר, כתובת, טקסט).</summary>
    public event Action<string, ulong, string>? TextReceived;
    /// <summary>כמו TextReceived, עם מזהה ההודעה (לאישור קריאה).</summary>
    public event Action<string, ulong, string, long>? TextReceivedEx;
    public event Action<string>? Error;

    public PeerServer(Func<Settings> settings) => _settings = settings;

    public async Task StartAsync()
    {
        _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(PeerProtocol.ServiceUuid));
        _listener = new StreamSocketListener();
        _listener.ConnectionReceived += (s, e) => { _ = HandleAsync(e.Socket); };
        // רק חיבור מאומת ומוצפן (מכשירים מצומדים)
        await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
        _provider.StartAdvertising(_listener, false);
    }

    public void Stop()
    {
        try { _provider?.StopAdvertising(); } catch { }
        _listener?.Dispose(); _listener = null; _provider = null;
        lock (_sessions) { foreach (var s in _sessions.ToArray()) s.Close(); _sessions.Clear(); }
    }

    public void Dispose() => Stop();

    static async Task<(string Name, bool Paired, ulong Address)> RemoteInfoAsync(StreamSocket sock)
    {
        try
        {
            var raw = sock.Information.RemoteHostName.RawName.Trim('(', ')').Replace(":", "");
            ulong addr = Convert.ToUInt64(raw, 16);
            var dev = await BluetoothDevice.FromBluetoothAddressAsync(addr);
            if (dev != null)
                return (string.IsNullOrWhiteSpace(dev.Name) ? "מכשיר לא מזוהה" : dev.Name, dev.DeviceInformation.Pairing.IsPaired, addr);
        }
        catch { }
        return ("מכשיר לא מזוהה", false, 0);
    }

    async Task HandleAsync(StreamSocket sock)
    {
        PeerSession? session = null;
        try
        {
            var (from, paired, addr) = await RemoteInfoAsync(sock);
            if (!paired) { sock.Dispose(); return; }
            var cfg = _settings();
            from = cfg.DisplayName(addr, from); // כינוי שהמשתמש נתן למכשיר
            session = new PeerSession(sock.InputStream.AsStreamForRead(), sock.OutputStream.AsStreamForWrite(), sock,
                cfg.GetOrCreateDeviceId(), Environment.MachineName, Path.Combine(Path.GetTempPath(), "SendEasyBT", "rx"), paired);
            session.OfferHandler = async f =>
            {
                var c = _settings();
                if (!c.ReceiveEnabled || (c.MaxReceiveBytes > 0 && f.Size > c.MaxReceiveBytes)) return PeerProtocol.RejectUser;
                return await Decide(f) ? 0 : PeerProtocol.RejectUser;
            };
            session.FileReceived = (f, temp) =>
            {
                try
                {
                    var c = _settings();
                    if (ChatLog.IsVoiceName(f.Name))
                    {
                        // הודעה קולית: נשמרת בתיקיית הקול ומופיעה רק בצ'אט
                        Directory.CreateDirectory(ChatLog.VoiceDir);
                        var v = FileNames.UniquePath(ChatLog.VoiceDir, f.Name);
                        File.Move(temp, v);
                        ReceivedFrom?.Invoke(addr, f, v);
                        return v;
                    }
                    Directory.CreateDirectory(c.ReceiveDir);
                    var dest = FileNames.UniquePath(c.ReceiveDir, f.Name);
                    File.Move(temp, dest);
                    ObexServer.MarkFromInternet(dest);
                    ReceivedFrom?.Invoke(addr, f, dest);
                    Received?.Invoke(f, dest);
                    return dest;
                }
                catch (Exception ex) { Error?.Invoke(ex.Message); return null; }
            };
            session.Progress += (f, got) => Progress?.Invoke(f, got);
            session.Failed += f => Aborted?.Invoke(f);
            session.TextReceived += t => TextReceived?.Invoke(session.PeerName.Length > 0 ? session.PeerName : from, addr, t);
            session.TextReceivedEx += (t, id) => TextReceivedEx?.Invoke(from, addr, t, id);
            PeerConnections.Register(addr, session); // אפשר לשלוח עליו הודעות ואישורי קריאה חזרה
            var s = session;
            session.Closed += ex => { lock (_sessions) _sessions.Remove(s); if (ex is not (null or EndOfStreamException or OperationCanceledException)) Error?.Invoke(ex.Message); };
            lock (_sessions) _sessions.Add(session);
            await session.StartAsync();
        }
        catch (Exception ex)
        {
            Error?.Invoke(ex.Message);
            session?.Close(ex);
            if (session == null) sock.Dispose();
        }
    }
}