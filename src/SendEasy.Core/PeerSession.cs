using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Channels;

namespace SendEasy.Core;

/// <summary>קבועי הפרוטוקול הפרטי. המפרט המלא ב-PROTOCOL.md בשורש המאגר.</summary>
public static class PeerProtocol
{
    public static readonly Guid ServiceUuid = Guid.Parse("7a3e1c5b-2f4d-4b8a-9c6e-5d1f0b2a8e47");
    public const int Version = 1;
    public const int MaxFrame = 65600;
    public const int Chunk = 16 * 1024;
    public const int MaxTextBytes = 16 * 1024;
    public const int MaxNameBytes = 255;
    public const long MaxFileSize = 4L * 1024 * 1024 * 1024;

    public const byte Hello = 0x01, Ping = 0x02, Pong = 0x03, Text = 0x10, Ack = 0x11, Read = 0x12,
        FileOffer = 0x20, FileAccept = 0x21, FileReject = 0x22, FileChunk = 0x23, FileEnd = 0x24,
        FileDone = 0x25, Cancel = 0x26, Error = 0xF0;

    public const int RejectUser = 1, RejectStorage = 2, RejectName = 3, RejectBusy = 4;
    public const int DoneOk = 0, DoneHash = 1, DoneIo = 2, DoneCancelled = 3;

    public const int PingIntervalMs = 8000, DeadAfterMs = 30000;
}

public enum PeerSendResult { Ok, Rejected, Cancelled, HashMismatch, RemoteError }

/// <summary>
/// חיבור אחד בפרוטוקול הפרטי: מסגור, לחיצת יד, פינג, טקסט וקבצים. עובד על זרמים רגילים.
/// זהה בהתנהגותו למחלקה Session באפליקציית האנדרואיד.
/// </summary>
public sealed class PeerSession : IDisposable
{
    sealed class Active
    {
        public required IncomingFile File;
        public long Id;
        public FileStream Out = null!;
        public IncrementalHash Hash = null!;
        public string Temp = "";
        public long Got;
    }

    readonly Stream _in, _out;
    readonly IDisposable _closer;
    readonly byte[] _myId;
    readonly string _myName;
    readonly string _tmpDir;
    readonly bool _paired;
    readonly SemaphoreSlim _wlock = new(1, 1);
    readonly SemaphoreSlim _sendLock = new(1, 1);
    readonly Channel<(byte Type, long Id, int Val)> _xfer = Channel.CreateUnbounded<(byte, long, int)>();
    readonly Channel<long> _acks = Channel.CreateUnbounded<long>();
    readonly TaskCompletionSource _hello = new(TaskCreationOptions.RunContinuationsAsynchronously);
    readonly CancellationTokenSource _cts = new();
    int _closed;
    long _lastRx = Environment.TickCount64;
    Active? _active;

    public byte[]? PeerId { get; private set; }
    public string PeerName { get; private set; } = "";
    public int LastRejectReason { get; private set; }
    public bool IsClosed => Volatile.Read(ref _closed) != 0;

    /// <summary>מחליט על הצעת קובץ נכנסת: 0 = לקבל, אחרת קוד סירוב.</summary>
    public Func<IncomingFile, Task<int>>? OfferHandler { get; set; }
    /// <summary>נקרא אחרי שה-SHA-256 תקין. מעביר את הקובץ הזמני ליעד ומחזיר את הנתיב הסופי, או null בכשל.</summary>
    public Func<IncomingFile, string, string?>? FileReceived { get; set; }
    public event Action<IncomingFile, long>? Progress;
    public event Action<IncomingFile>? Failed;
    public event Action<string>? TextReceived;
    /// <summary>כמו TextReceived, עם מזהה ההודעה (לאישור קריאה).</summary>
    public event Action<string, long>? TextReceivedEx;
    /// <summary>הצד השני קרא הודעה שלנו (מזהה ההודעה).</summary>
    public event Action<long>? ReadReceived;
    public ulong RemoteAddress { get; set; }
    public event Action<Exception?>? Closed;

    public PeerSession(Stream input, Stream output, IDisposable closer, byte[] myId, string myName, string tmpDir, bool paired)
    {
        _in = input; _out = output; _closer = closer; _myId = myId; _myName = myName; _tmpDir = tmpDir; _paired = paired;
    }

    public async Task StartAsync()
    {
        _ = Task.Run(ReadLoopAsync);
        var nm = Encoding.UTF8.GetBytes(_myName);
        if (nm.Length > 64) nm = nm[..64];
        var hello = new byte[1 + 2 + 16 + 1 + nm.Length];
        hello[0] = PeerProtocol.Version;
        Array.Copy(_myId, 0, hello, 3, 16);
        hello[19] = (byte)nm.Length;
        Array.Copy(nm, 0, hello, 20, nm.Length);
        await SendFrameAsync(PeerProtocol.Hello, hello, default);
        _ = Task.Run(PingLoopAsync);
    }

    public async Task<bool> WaitHelloAsync(TimeSpan timeout)
    {
        var done = await Task.WhenAny(_hello.Task, Task.Delay(timeout));
        return done == _hello.Task && !IsClosed;
    }

    // ------------------------------------------------------------ כתיבה

    async Task SendFrameAsync(byte type, ReadOnlyMemory<byte> head, ReadOnlyMemory<byte> body)
    {
        await _wlock.WaitAsync(_cts.Token);
        try
        {
            if (IsClosed) throw new IOException("החיבור נסגר");
            var hdr = new byte[6];
            BinaryPrimitives.WriteInt32BigEndian(hdr, 2 + head.Length + body.Length);
            hdr[4] = type;
            await _out.WriteAsync(hdr);
            if (head.Length > 0) await _out.WriteAsync(head);
            if (body.Length > 0) await _out.WriteAsync(body);
            await _out.FlushAsync();
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            Close(ex);
            throw;
        }
        finally { _wlock.Release(); }
    }

    static byte[] Id8(long v) { var b = new byte[8]; BinaryPrimitives.WriteInt64BigEndian(b, v); return b; }
    static byte[] Cat(params byte[][] parts) => parts.SelectMany(p => p).ToArray();

    // ------------------------------------------------------------ קריאה

    async Task ReadLoopAsync()
    {
        Exception? why = null;
        try
        {
            var hdr = new byte[6];
            while (!IsClosed)
            {
                await ReadExactAsync(hdr, 6);
                int len = BinaryPrimitives.ReadInt32BigEndian(hdr);
                if (len < 2 || len > PeerProtocol.MaxFrame) throw new IOException($"מסגרת לא תקינה ({len})");
                byte type = hdr[4];
                if (hdr[5] != 0) throw new IOException("דגלים לא נתמכים");
                var p = new byte[len - 2];
                await ReadExactAsync(p, p.Length);
                Volatile.Write(ref _lastRx, Environment.TickCount64);
                await DispatchAsync(type, p);
            }
        }
        catch (Exception e) { why = e; }
        Close(why);
    }

    async Task ReadExactAsync(byte[] buf, int count)
    {
        int got = 0;
        while (got < count)
        {
            int n = await _in.ReadAsync(buf.AsMemory(got, count - got), _cts.Token);
            if (n <= 0) throw new EndOfStreamException("החיבור נסגר");
            got += n;
        }
    }

    async Task DispatchAsync(byte type, byte[] p)
    {
        switch (type)
        {
            case PeerProtocol.Hello: await OnHelloAsync(p); break;
            case PeerProtocol.Ping: await SendFrameAsync(PeerProtocol.Pong, default, default); break;
            case PeerProtocol.Pong: break;
            case PeerProtocol.Text: await OnTextAsync(p); break;
            case PeerProtocol.Read:
                if (p.Length >= 8) ReadReceived?.Invoke(BinaryPrimitives.ReadInt64BigEndian(p));
                break;
            case PeerProtocol.Ack:
                if (p.Length >= 8) _acks.Writer.TryWrite(BinaryPrimitives.ReadInt64BigEndian(p));
                break;
            case PeerProtocol.FileOffer: await OnOfferAsync(p); break;
            case PeerProtocol.FileChunk: await OnChunkAsync(p); break;
            case PeerProtocol.FileEnd: await OnEndAsync(p); break;
            case PeerProtocol.Cancel:
                if (p.Length >= 8)
                {
                    long id = BinaryPrimitives.ReadInt64BigEndian(p);
                    if (_active != null && _active.Id == id) FailActive();
                    else _xfer.Writer.TryWrite((PeerProtocol.Cancel, id, 0));
                }
                break;
            case PeerProtocol.FileAccept:
                if (p.Length >= 8) _xfer.Writer.TryWrite((type, BinaryPrimitives.ReadInt64BigEndian(p), 0));
                break;
            case PeerProtocol.FileReject:
            case PeerProtocol.FileDone:
                if (p.Length >= 9) _xfer.Writer.TryWrite((type, BinaryPrimitives.ReadInt64BigEndian(p), p[8]));
                break;
            case PeerProtocol.Error: throw new IOException("הצד השני דיווח על שגיאה");
            default: break; // סוג לא מוכר: מתעלמים (תאימות קדימה)
        }
    }

    async Task OnHelloAsync(byte[] p)
    {
        if (p.Length < 20) throw new IOException("HELLO קצר מדי");
        if (p[0] != PeerProtocol.Version)
        {
            try { await SendFrameAsync(PeerProtocol.Error, Cat(new byte[] { 1 }, Encoding.UTF8.GetBytes("גרסת פרוטוקול לא נתמכת")), default); } catch { }
            throw new IOException("גרסת פרוטוקול לא תואמת: " + p[0]);
        }
        PeerId = p[3..19];
        int nl = Math.Min(p[19], p.Length - 20);
        PeerName = Encoding.UTF8.GetString(p, 20, nl);
        _hello.TrySetResult();
    }

    async Task OnTextAsync(byte[] p)
    {
        if (p.Length < 8) return;
        long id = BinaryPrimitives.ReadInt64BigEndian(p);
        int n = p.Length - 8;
        if (n > PeerProtocol.MaxTextBytes) return;
        var text = Encoding.UTF8.GetString(p, 8, n);
        TextReceived?.Invoke(text);
        TextReceivedEx?.Invoke(text, id);
        await SendFrameAsync(PeerProtocol.Ack, Id8(id), default);
    }

    Task RejectAsync(long id, int reason) =>
        SendFrameAsync(PeerProtocol.FileReject, Cat(Id8(id), new[] { (byte)reason }), default);

    async Task OnOfferAsync(byte[] p)
    {
        if (p.Length < 18) return;
        long id = BinaryPrimitives.ReadInt64BigEndian(p);
        long size = BinaryPrimitives.ReadInt64BigEndian(p.AsSpan(8));
        int nl = (p[16] << 8) | p[17];
        if (nl > PeerProtocol.MaxNameBytes || 18 + nl > p.Length) { await RejectAsync(id, PeerProtocol.RejectName); return; }
        if (_active != null) { await RejectAsync(id, PeerProtocol.RejectBusy); return; }
        var name = FileNames.Sanitize(Encoding.UTF8.GetString(p, 18, nl));
        if (name.Length == 0 || size < 0 || size > PeerProtocol.MaxFileSize) { await RejectAsync(id, PeerProtocol.RejectName); return; }

        var file = new IncomingFile(PeerName, name, size, _paired);
        int r = OfferHandler != null ? await OfferHandler(file) : 0;
        if (r != 0) { await RejectAsync(id, r); return; }

        Active a;
        try
        {
            Directory.CreateDirectory(_tmpDir);
            var temp = Path.Combine(_tmpDir, $"rx_{id:x}.part");
            a = new Active
            {
                File = file, Id = id, Temp = temp,
                Out = new FileStream(temp, FileMode.Create, FileAccess.Write),
                Hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256),
            };
        }
        catch (Exception) { await RejectAsync(id, PeerProtocol.RejectStorage); return; }
        _active = a;
        await SendFrameAsync(PeerProtocol.FileAccept, Cat(Id8(id), Id8(0)), default);
    }

    async Task OnChunkAsync(byte[] p)
    {
        var a = _active;
        if (a == null || p.Length < 8 || BinaryPrimitives.ReadInt64BigEndian(p) != a.Id) return;
        if (a.File.Cts.IsCancellationRequested)
        {
            await SendFrameAsync(PeerProtocol.Cancel, Id8(a.Id), default);
            FailActive();
            return;
        }
        int n = p.Length - 8;
        if (a.Got + n > a.File.Size) { await DoneAsync(a.Id, PeerProtocol.DoneIo); FailActive(); return; }
        try { await a.Out.WriteAsync(p.AsMemory(8, n)); }
        catch (IOException) { await DoneAsync(a.Id, PeerProtocol.DoneIo); FailActive(); return; }
        a.Hash.AppendData(p, 8, n);
        a.Got += n;
        Progress?.Invoke(a.File, a.Got);
    }

    Task DoneAsync(long id, int status) =>
        SendFrameAsync(PeerProtocol.FileDone, Cat(Id8(id), new[] { (byte)status }), default);

    async Task OnEndAsync(byte[] p)
    {
        var a = _active;
        if (a == null || p.Length < 40 || BinaryPrimitives.ReadInt64BigEndian(p) != a.Id) return;
        int status;
        try { await a.Out.DisposeAsync(); }
        catch (IOException) { await DoneAsync(a.Id, PeerProtocol.DoneIo); FailActive(); return; }

        if (a.Got != a.File.Size) status = PeerProtocol.DoneIo;
        else if (!CryptographicOperations.FixedTimeEquals(p.AsSpan(8, 32), a.Hash.GetHashAndReset())) status = PeerProtocol.DoneHash;
        else
        {
            string? dest = null;
            try { dest = FileReceived?.Invoke(a.File, a.Temp); } catch { }
            status = dest != null ? PeerProtocol.DoneOk : PeerProtocol.DoneIo;
        }
        _active = null;
        a.Hash.Dispose();
        try { if (File.Exists(a.Temp)) File.Delete(a.Temp); } catch { }
        await DoneAsync(a.Id, status);
        if (status != PeerProtocol.DoneOk) Failed?.Invoke(a.File);
    }

    void FailActive()
    {
        var a = _active;
        _active = null;
        if (a == null) return;
        try { a.Out.Dispose(); } catch { }
        a.Hash.Dispose();
        try { File.Delete(a.Temp); } catch { }
        Failed?.Invoke(a.File);
    }

    // ------------------------------------------------------------ פינג

    async Task PingLoopAsync()
    {
        long lastPing = 0;
        try
        {
            while (!IsClosed)
            {
                await Task.Delay(1000, _cts.Token);
                long now = Environment.TickCount64;
                if (now - Volatile.Read(ref _lastRx) > PeerProtocol.DeadAfterMs) { Close(new IOException("אין תקשורת מהצד השני")); return; }
                if (now - lastPing >= PeerProtocol.PingIntervalMs)
                {
                    lastPing = now;
                    await SendFrameAsync(PeerProtocol.Ping, default, default);
                }
            }
        }
        catch { }
    }

    // ------------------------------------------------------------ שליחה

    public async Task<bool> SendTextAsync(string text, CancellationToken ct = default) => (await SendTextWithIdAsync(text, ct)).Ok;

    /// <summary>מאשר שקראנו הודעה של הצד השני (וי ירוק אצלו).</summary>
    public Task SendReadAsync(long msgId) => SendFrameAsync(PeerProtocol.Read, Id8(msgId), default);

    public async Task<(bool Ok, long Id)> SendTextWithIdAsync(string text, CancellationToken ct = default)
    {
        var t = Encoding.UTF8.GetBytes(text);
        if (t.Length > PeerProtocol.MaxTextBytes) throw new IOException("ההודעה ארוכה מדי");
        long id = Random.Shared.NextInt64(1, long.MaxValue);
        while (_acks.Reader.TryRead(out _)) { }
        await SendFrameAsync(PeerProtocol.Text, Id8(id), t);
        using var to = CancellationTokenSource.CreateLinkedTokenSource(ct, _cts.Token);
        to.CancelAfter(10000);
        try
        {
            while (true) if (await _acks.Reader.ReadAsync(to.Token) == id) return (true, id);
        }
        catch (OperationCanceledException) { return (false, id); }
    }

    async Task<(byte Type, long Id, int Val)> WaitEventAsync(long id, TimeSpan timeout, CancellationToken ct)
    {
        using var to = CancellationTokenSource.CreateLinkedTokenSource(ct, _cts.Token);
        to.CancelAfter(timeout);
        try
        {
            while (true)
            {
                var ev = await _xfer.Reader.ReadAsync(to.Token);
                if (ev.Id == id) return ev;
            }
        }
        catch (OperationCanceledException)
        {
            if (ct.IsCancellationRequested) return (PeerProtocol.Cancel, id, 0);
            throw new IOException(IsClosed ? "החיבור נסגר" : "הצד השני לא הגיב");
        }
    }

    /// <summary>שולח קובץ אחד. קובץ אחד בכל רגע: שליחות נוספות ממתינות.</summary>
    public async Task<PeerSendResult> SendFileAsync(Stream src, string name, long size, IProgress<long>? progress, CancellationToken ct)
    {
        await _sendLock.WaitAsync(ct);
        try
        {
            var nb = Encoding.UTF8.GetBytes(name);
            if (nb.Length > PeerProtocol.MaxNameBytes) nb = nb[..PeerProtocol.MaxNameBytes];
            long id = Random.Shared.NextInt64(1, long.MaxValue);
            while (_xfer.Reader.TryRead(out _)) { }
            var head = new byte[8 + 8 + 2 + nb.Length];
            BinaryPrimitives.WriteInt64BigEndian(head, id);
            BinaryPrimitives.WriteInt64BigEndian(head.AsSpan(8), size);
            head[16] = (byte)(nb.Length >> 8); head[17] = (byte)nb.Length;
            nb.CopyTo(head, 18);
            await SendFrameAsync(PeerProtocol.FileOffer, head, default);

            var ev = await WaitEventAsync(id, TimeSpan.FromSeconds(60), ct);
            if (ev.Type == PeerProtocol.Cancel) { await SendFrameAsync(PeerProtocol.Cancel, Id8(id), default); return PeerSendResult.Cancelled; }
            if (ev.Type == PeerProtocol.FileReject) { LastRejectReason = ev.Val; return PeerSendResult.Rejected; }

            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var idb = Id8(id);
            var buf = new byte[PeerProtocol.Chunk];
            long sent = 0;
            while (true)
            {
                int n = 0;
                try
                {
                    while (n < buf.Length)
                    {
                        int r = await src.ReadAsync(buf.AsMemory(n), ct);
                        if (r <= 0) break;
                        n += r;
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    await SendFrameAsync(PeerProtocol.Cancel, idb, default);
                    return PeerSendResult.Cancelled;
                }
                if (n == 0) break;
                if (ct.IsCancellationRequested) { await SendFrameAsync(PeerProtocol.Cancel, idb, default); return PeerSendResult.Cancelled; }
                if (_xfer.Reader.TryRead(out var remote) && remote.Id == id)
                {
                    if (remote.Type == PeerProtocol.Cancel) return PeerSendResult.Cancelled;
                    if (remote.Type == PeerProtocol.FileDone) return PeerSendResult.RemoteError;
                }
                hash.AppendData(buf, 0, n);
                await SendFrameAsync(PeerProtocol.FileChunk, idb, buf.AsMemory(0, n));
                sent += n;
                progress?.Report(sent);
            }
            if (sent != size)
            {
                await SendFrameAsync(PeerProtocol.Cancel, idb, default);
                throw new IOException("גודל הקובץ השתנה בזמן השליחה");
            }
            await SendFrameAsync(PeerProtocol.FileEnd, Cat(idb, hash.GetHashAndReset()), default);

            ev = await WaitEventAsync(id, TimeSpan.FromSeconds(120), ct);
            if (ev.Type == PeerProtocol.Cancel) return PeerSendResult.Cancelled;
            return ev.Val switch
            {
                PeerProtocol.DoneOk => PeerSendResult.Ok,
                PeerProtocol.DoneHash => PeerSendResult.HashMismatch,
                PeerProtocol.DoneCancelled => PeerSendResult.Cancelled,
                _ => PeerSendResult.RemoteError,
            };
        }
        finally { _sendLock.Release(); }
    }

    // ------------------------------------------------------------ סגירה

    public void Close(Exception? why = null)
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        try { _cts.Cancel(); } catch { }
        try { _closer.Dispose(); } catch { }
        _hello.TrySetResult();
        FailActive();
        Closed?.Invoke(why);
    }

    public void Dispose() => Close();
}