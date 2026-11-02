using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace SendEasy.Core;

/// <summary>הצעת קישור וויפי: פורט, מפתח חד-פעמי וכתובות ה-IPv4 של המקבל. עוברת רק בתוך חיבור הבלוטוס המוצפן. המפרט ב-PROTOCOL.md.</summary>
public sealed record WifiOffer(ushort Port, byte[] Key, string[] Ips)
{
    public byte[] ToBytes()
    {
        var ips = Ips.Select(IPAddress.Parse).Where(a => a.AddressFamily == AddressFamily.InterNetwork).Take(8).ToArray();
        var b = new byte[2 + 32 + 1 + 4 * ips.Length];
        BinaryPrimitives.WriteUInt16BigEndian(b, Port);
        Key.CopyTo(b, 2);
        b[34] = (byte)ips.Length;
        for (int i = 0; i < ips.Length; i++) ips[i].GetAddressBytes().CopyTo(b, 35 + 4 * i);
        return b;
    }

    public static WifiOffer? Parse(byte[] p)
    {
        if (p.Length < 35) return null;
        int n = p[34];
        if (p.Length < 35 + 4 * n) return null;
        var ips = Enumerable.Range(0, n).Select(i => new IPAddress(p.AsSpan(35 + 4 * i, 4)).ToString()).ToArray();
        return new WifiOffer(BinaryPrimitives.ReadUInt16BigEndian(p), p[2..34], ips);
    }
}

/// <summary>
/// זרם מוצפן מעל TCP: כל מסגרת היא [u32 אורך][AES-256-GCM(מטען)+תג 16]. nonce = 4 בתי אפס + מונה u64 לכל כיוון,
/// ומפתח נפרד לכל כיוון (HMAC-SHA256 של המפתח החד-פעמי). אימות הצד השני הוא הצלחת פענוח המסגרת הראשונה.
/// </summary>
public sealed class EncryptedStream : Stream
{
    const int MaxPlain = 60 * 1024;
    readonly Stream _net;
    readonly AesGcm _tx, _rx;
    ulong _txCount, _rxCount;
    readonly byte[] _wbuf = new byte[MaxPlain];
    int _wlen;
    byte[] _rbuf = Array.Empty<byte>();
    int _rpos;

    public EncryptedStream(Stream net, byte[] key, bool client)
    {
        _net = net;
        var c2s = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes("se-wifi c2s"));
        var s2c = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes("se-wifi s2c"));
        _tx = new AesGcm(client ? c2s : s2c, 16);
        _rx = new AesGcm(client ? s2c : c2s, 16);
    }

    static byte[] Nonce(ulong n)
    {
        var b = new byte[12];
        BinaryPrimitives.WriteUInt64BigEndian(b.AsSpan(4), n);
        return b;
    }

    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
    public override void SetLength(long v) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => WriteAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct = default)
    {
        while (data.Length > 0)
        {
            int n = Math.Min(data.Length, MaxPlain - _wlen);
            data.Span[..n].CopyTo(_wbuf.AsSpan(_wlen));
            _wlen += n;
            data = data[n..];
            if (_wlen == MaxPlain) await FlushAsync(ct);
        }
    }

    public override void Flush() => FlushAsync(default).GetAwaiter().GetResult();

    public override async Task FlushAsync(CancellationToken ct)
    {
        if (_wlen > 0)
        {
            var frame = new byte[4 + _wlen + 16];
            BinaryPrimitives.WriteInt32BigEndian(frame, _wlen + 16);
            _tx.Encrypt(Nonce(_txCount++), _wbuf.AsSpan(0, _wlen), frame.AsSpan(4, _wlen), frame.AsSpan(4 + _wlen, 16));
            _wlen = 0;
            await _net.WriteAsync(frame, ct);
        }
        await _net.FlushAsync(ct);
    }

    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    public override async ValueTask<int> ReadAsync(Memory<byte> dest, CancellationToken ct = default)
    {
        if (_rpos >= _rbuf.Length)
        {
            var hdr = new byte[4];
            if (!await ReadFullAsync(hdr, ct)) return 0;
            int len = BinaryPrimitives.ReadInt32BigEndian(hdr);
            if (len < 16 || len > MaxPlain + 16) throw new IOException("מסגרת וויפי לא תקינה");
            var ct1 = new byte[len];
            if (!await ReadFullAsync(ct1, ct)) throw new EndOfStreamException();
            var plain = new byte[len - 16];
            try { _rx.Decrypt(Nonce(_rxCount++), ct1.AsSpan(0, len - 16), ct1.AsSpan(len - 16), plain); }
            catch (CryptographicException) { throw new IOException("אימות קישור הוויפי נכשל"); }
            _rbuf = plain; _rpos = 0;
        }
        int n = Math.Min(dest.Length, _rbuf.Length - _rpos);
        _rbuf.AsMemory(_rpos, n).CopyTo(dest);
        _rpos += n;
        return n;
    }

    async Task<bool> ReadFullAsync(byte[] buf, CancellationToken ct)
    {
        int got = 0;
        while (got < buf.Length)
        {
            int n = await _net.ReadAsync(buf.AsMemory(got), ct);
            if (n <= 0) { if (got == 0) return false; throw new EndOfStreamException(); }
            got += n;
        }
        return true;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { _tx.Dispose(); _rx.Dispose(); _net.Dispose(); }
        base.Dispose(disposing);
    }
}

/// <summary>קישור TCP מוצפן ברשת מקומית משותפת (בלי אינטרנט): צד אחד מאזין, הצד השני מתחבר.</summary>
public static class WifiLink
{
    public const int Cap = 1;

    /// <summary>כתובות IPv4 של כרטיסי רשת פעילים (לא לולאה ולא 169.254).</summary>
    public static string[] LocalIps()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !a.ToString().StartsWith("169.254."))
                .Select(a => a.ToString()).Distinct().ToArray();
        }
        catch { return Array.Empty<string>(); }
    }

    /// <summary>פותח מאזין לחיבור אחד. מחזיר את ההצעה לשליחה, ו-accept שממתין לחיבור (ונסגר אחריו או בפקיעת הזמן).</summary>
    public static (WifiOffer Offer, Func<Task<(EncryptedStream Stream, IDisposable Closer)?>> Accept)? Listen(TimeSpan wait)
    {
        var ips = LocalIps();
        if (ips.Length == 0) return null;
        TcpListener l;
        try { l = new TcpListener(IPAddress.Any, 0); l.Start(1); }
        catch { return null; }
        var key = RandomNumberGenerator.GetBytes(32);
        var offer = new WifiOffer((ushort)((IPEndPoint)l.LocalEndpoint).Port, key, ips);
        async Task<(EncryptedStream, IDisposable)?> Accept()
        {
            try
            {
                using var cts = new CancellationTokenSource(wait);
                var c = await l.AcceptTcpClientAsync(cts.Token);
                c.NoDelay = true;
                return (new EncryptedStream(c.GetStream(), key, client: false), c);
            }
            catch { return null; }
            finally { try { l.Stop(); } catch { } }
        }
        return (offer, Accept);
    }

    /// <summary>מתחבר לכתובות במקביל (3 שניות). null אם אין נתיב.</summary>
    public static async Task<(EncryptedStream Stream, IDisposable Closer)?> ConnectAsync(WifiOffer o, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(3));
        var tasks = o.Ips.Select(async ip =>
        {
            var c = new TcpClient { NoDelay = true };
            try { await c.ConnectAsync(IPAddress.Parse(ip), o.Port, cts.Token); return c; }
            catch { c.Dispose(); return null; }
        }).ToList();
        TcpClient? won = null;
        while (tasks.Count > 0)
        {
            var t = await Task.WhenAny(tasks);
            tasks.Remove(t);
            var c = await t;
            if (c != null && won == null) { won = c; cts.Cancel(); }
            else c?.Dispose();
        }
        if (won == null) return null;
        return (new EncryptedStream(won.GetStream(), o.Key, client: true), won);
    }
}
