using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace SendEasy.Core;

public sealed record IncomingFile(string FromName, string Name, long Size, bool FromPaired)
{
    /// <summary>ביטול הקבלה מצד המשתמש (למשל מחלון ההתקדמות).</summary>
    public CancellationTokenSource Cts { get; } = new();
}

/// <summary>שרת קבלה OBEX Object Push. נרשם כשירות 0x1105 כך שכל טלפון יכול לשלוח למחשב.</summary>
public sealed class ObexServer : IDisposable
{
    readonly Func<Settings> _settings;
    RfcommServiceProvider? _provider;
    StreamSocketListener? _listener;

    /// <summary>נקרא לפני קבלה. מחזיר true לקבל.</summary>
    public Func<IncomingFile, Task<bool>> Decide { get; set; } = _ => Task.FromResult(true);
    public event Action<IncomingFile, long>? Progress;
    /// <summary>הקובץ התקבל במלואו ונשמר בנתיב הנתון.</summary>
    public event Action<IncomingFile, string>? Received;
    public event Action<string>? Error;
    /// <summary>קבלה שהופסקה לפני שהסתיימה (ביטול או ניתוק).</summary>
    public event Action<IncomingFile>? Aborted;

    public ObexServer(Func<Settings> settings) => _settings = settings;

    public async Task StartAsync()
    {
        _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.ObexObjectPush);
        _listener = new StreamSocketListener();
        _listener.ConnectionReceived += (s, e) => { _ = HandleAsync(e.Socket); };
        await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(),
            SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
        _provider.StartAdvertising(_listener, true);
    }

    public void Stop()
    {
        try { _provider?.StopAdvertising(); } catch { }
        _listener?.Dispose(); _listener = null; _provider = null;
    }

    public void Dispose() => Stop();

    static async Task<(string Name, bool Paired)> RemoteInfoAsync(StreamSocket sock, Settings cfg)
    {
        try
        {
            var raw = sock.Information.RemoteHostName.RawName.Trim('(', ')').Replace(":", "");
            ulong addr = Convert.ToUInt64(raw, 16);
            var dev = await BluetoothDevice.FromBluetoothAddressAsync(addr);
            if (dev != null)
                return (cfg.DisplayName(addr, string.IsNullOrWhiteSpace(dev.Name) ? "מכשיר לא מזוהה" : dev.Name),
                        dev.DeviceInformation.Pairing.IsPaired);
        }
        catch { }
        return ("מכשיר לא מזוהה", false);
    }

    async Task HandleAsync(StreamSocket sock)
    {
        string? tmp = null;
        FileStream? fs = null;
        IncomingFile? active = null;
        try
        {
            using var _ = sock;
            var cfg = _settings();
            Directory.CreateDirectory(cfg.ReceiveDir);
            var r = new DataReader(sock.InputStream);
            var w = new DataWriter(sock.OutputStream);
            var (from, paired) = await RemoteInfoAsync(sock, cfg);
            IncomingFile? file = null; long got = 0;

            while (true)
            {
                var p = await ObexIo.ReadPacketAsync(r);
                byte op = (byte)(p[0] & 0x7F);
                bool final = (p[0] & 0x80) != 0;

                if (op == 0x00) { await ObexIo.WriteAsync(w, ObexIo.Ok, new byte[] { 0x10, 0x00, 0x20, 0x00 }); continue; }
                if (op == 0x01) { await ObexIo.WriteAsync(w, ObexIo.Ok); return; }
                if (op == 0x7F) { Cleanup(ref fs, ref tmp); await ObexIo.WriteAsync(w, ObexIo.Ok); continue; }
                if (op != 0x02) { await ObexIo.WriteAsync(w, ObexIo.NotImplemented); continue; }

                var h = ObexIo.ParseHeaders(p, 3);
                if (file == null)
                {
                    var name = FileNames.Sanitize(h.Name ?? "file");
                    file = new IncomingFile(from, name, h.Length ?? -1, paired);
                    if (!cfg.ReceiveEnabled || (cfg.MaxReceiveBytes > 0 && file.Size > cfg.MaxReceiveBytes)
                        || !await Decide(file))
                    {
                        await ObexIo.WriteAsync(w, ObexIo.Forbidden);
                        return;
                    }
                    tmp = FileNames.UniquePath(cfg.ReceiveDir, name + ".part");
                    fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write);
                    got = 0; active = file;
                }
                if (h.Body is { Length: > 0 })
                {
                    got += h.Body.Length;
                    if (cfg.MaxReceiveBytes > 0 && got > cfg.MaxReceiveBytes) { await ObexIo.WriteAsync(w, ObexIo.Forbidden); return; }
                    fs!.Write(h.Body);
                    Progress?.Invoke(file, got);
                    if (file.Cts.IsCancellationRequested) { await ObexIo.WriteAsync(w, ObexIo.Forbidden); return; }
                }
                if (final)
                {
                    fs!.Dispose(); fs = null;
                    var dest = FileNames.UniquePath(cfg.ReceiveDir, file.Name);
                    File.Move(tmp!, dest); tmp = null;
                    MarkFromInternet(dest);
                    await ObexIo.WriteAsync(w, ObexIo.Ok);
                    active = null;
                    Received?.Invoke(file, dest);
                    file = null;
                }
                else await ObexIo.WriteAsync(w, ObexIo.Continue);
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException
                                    or UnauthorizedAccessException or System.Runtime.InteropServices.COMException)
        {
            Error?.Invoke(ex.Message);
        }
        finally
        {
            Cleanup(ref fs, ref tmp);
            if (active != null) Aborted?.Invoke(active);
        }
    }

    static void Cleanup(ref FileStream? fs, ref string? tmp)
    {
        try { fs?.Dispose(); } catch { }
        try { if (tmp != null) File.Delete(tmp); } catch { }
        fs = null; tmp = null;
    }

    /// <summary>סימון Mark of the Web, כדי ש-SmartScreen ידע שהקובץ הגיע מבחוץ.</summary>
    internal static void MarkFromInternet(string path)
    {
        try { File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n"); } catch { }
    }
}
