using System.IO.Pipes;
using System.Security.Cryptography;
using SendEasy.Core;
using Xunit;

/// <summary>בדיקות הפרוטוקול הפרטי: שני צדדים מחוברים בזרמים בזיכרון (כמו שני קצוות בלוטוס).</summary>
public class PeerSessionTests : IDisposable
{
    readonly string _tmp = Path.Combine(Path.GetTempPath(), "sebt-peer-" + Guid.NewGuid().ToString("N"));
    readonly List<IDisposable> _toDispose = new();

    public PeerSessionTests() => Directory.CreateDirectory(_tmp);

    public void Dispose()
    {
        foreach (var d in _toDispose) try { d.Dispose(); } catch { }
        try { Directory.Delete(_tmp, true); } catch { }
    }

    sealed class Closer : IDisposable
    {
        readonly IDisposable[] _items;
        public Closer(params IDisposable[] items) => _items = items;
        public void Dispose() { foreach (var i in _items) try { i.Dispose(); } catch { } }
    }

    /// <summary>שני PeerSession מחוברים זה לזה. A שולח, B מקבל.</summary>
    (PeerSession A, PeerSession B) Pair()
    {
        var ab = new AnonymousPipeServerStream(PipeDirection.Out, HandleInheritability.None);
        var abIn = new AnonymousPipeClientStream(PipeDirection.In, ab.ClientSafePipeHandle);
        var ba = new AnonymousPipeServerStream(PipeDirection.Out, HandleInheritability.None);
        var baIn = new AnonymousPipeClientStream(PipeDirection.In, ba.ClientSafePipeHandle);
        var a = new PeerSession(baIn, ab, new Closer(ab, baIn), Enumerable.Repeat((byte)1, 16).ToArray(), "A", Path.Combine(_tmp, "a"), true);
        var b = new PeerSession(abIn, ba, new Closer(ba, abIn), Enumerable.Repeat((byte)2, 16).ToArray(), "B", Path.Combine(_tmp, "b"), true);
        _toDispose.Add(a); _toDispose.Add(b);
        return (a, b);
    }

    async Task StartBoth(PeerSession a, PeerSession b)
    {
        await Task.WhenAll(a.StartAsync(), b.StartAsync());
        Assert.True(await a.WaitHelloAsync(TimeSpan.FromSeconds(5)));
        Assert.True(await b.WaitHelloAsync(TimeSpan.FromSeconds(5)));
    }

    static byte[] RandomBytes(int n) => RandomNumberGenerator.GetBytes(n);

    sealed class SyncProgress : IProgress<long>
    {
        readonly Action<long> _a;
        public SyncProgress(Action<long> a) => _a = a;
        public void Report(long v) => _a(v);
    }

    [Fact]
    public async Task Hello_ExchangesNamesAndIds()
    {
        var (a, b) = Pair();
        await StartBoth(a, b);
        Assert.Equal("B", a.PeerName);
        Assert.Equal("A", b.PeerName);
        Assert.Equal(Enumerable.Repeat((byte)2, 16), a.PeerId!);
    }

    [Fact]
    public async Task Text_IsDeliveredAndAcknowledged()
    {
        var (a, b) = Pair();
        string? got = null;
        b.TextReceived += t => got = t;
        await StartBoth(a, b);
        Assert.True(await a.SendTextAsync("שלום עולם\nשורה שנייה"));
        Assert.Equal("שלום עולם\nשורה שנייה", got);
    }

    [Fact]
    public async Task Read_Receipt_RoundTripsMessageId()
    {
        var (a, b) = Pair();
        long seenId = 0, readId = 0;
        b.TextReceivedEx += (_, id) => seenId = id;
        a.ReadReceived += id => readId = id;
        await StartBoth(a, b);
        var r = await a.SendTextWithIdAsync("שלום");
        Assert.True(r.Ok);
        Assert.Equal(r.Id, seenId);
        await b.SendReadAsync(seenId);
        for (int i = 0; i < 50 && readId == 0; i++) await Task.Delay(20);
        Assert.Equal(r.Id, readId);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(16 * 1024)]
    [InlineData(16 * 1024 + 1)]
    [InlineData(3 * 1024 * 1024 + 7)]
    public async Task File_RoundTrip_PreservesContent(int size)
    {
        var (a, b) = Pair();
        var data = RandomBytes(size);
        string? finalPath = null;
        b.FileReceived = (f, temp) =>
        {
            finalPath = Path.Combine(_tmp, "out_" + f.Name);
            File.Move(temp, finalPath);
            return finalPath;
        };
        long lastProgress = 0;
        b.Progress += (_, got) => lastProgress = got;
        await StartBoth(a, b);

        var r = await a.SendFileAsync(new MemoryStream(data), "דוגמה.bin", size, null, CancellationToken.None);
        Assert.Equal(PeerSendResult.Ok, r);
        Assert.NotNull(finalPath);
        Assert.Equal(data, await File.ReadAllBytesAsync(finalPath!));
        Assert.Equal(size, lastProgress);
    }

    [Fact]
    public async Task File_Rejected_ReportsReason()
    {
        var (a, b) = Pair();
        b.OfferHandler = _ => Task.FromResult(PeerProtocol.RejectUser);
        await StartBoth(a, b);
        var r = await a.SendFileAsync(new MemoryStream(RandomBytes(100)), "x.bin", 100, null, CancellationToken.None);
        Assert.Equal(PeerSendResult.Rejected, r);
        Assert.Equal(PeerProtocol.RejectUser, a.LastRejectReason);
    }

    [Fact]
    public async Task File_HandlerFailure_ReportsRemoteError()
    {
        var (a, b) = Pair();
        b.FileReceived = (_, _) => null; // כתיבה ליעד נכשלה
        await StartBoth(a, b);
        var r = await a.SendFileAsync(new MemoryStream(RandomBytes(5000)), "x.bin", 5000, null, CancellationToken.None);
        Assert.Equal(PeerSendResult.RemoteError, r);
    }

    [Fact]
    public async Task File_CancelFromSender_StopsAndLeavesNoFile()
    {
        var (a, b) = Pair();
        bool received = false, failed = false;
        b.FileReceived = (_, _) => { received = true; return null; };
        b.Failed += _ => failed = true;
        await StartBoth(a, b);

        using var cts = new CancellationTokenSource();
        var data = RandomBytes(5 * 1024 * 1024);
        // התקדמות סינכרונית: Progress<T> מדווח אסינכרונית, והשליחה כבר הייתה מסתיימת לפני הביטול
        var progress = new SyncProgress(sent => { if (sent > 64 * 1024) cts.Cancel(); });
        var r = await a.SendFileAsync(new MemoryStream(data), "big.bin", data.Length, progress, cts.Token);
        Assert.Equal(PeerSendResult.Cancelled, r);
        await Task.Delay(500);
        Assert.False(received);
        Assert.True(failed);
        Assert.Empty(Directory.Exists(Path.Combine(_tmp, "b")) ? Directory.GetFiles(Path.Combine(_tmp, "b")) : Array.Empty<string>());
    }

    [Fact]
    public async Task File_CancelFromReceiver_StopsSender()
    {
        var (a, b) = Pair();
        // המקבל מבטל מיד עם הקבלה: דטרמיניסטי, בלי תלות במהירות המכונה
        b.OfferHandler = f => { f.Cts.Cancel(); return Task.FromResult(0); };
        await StartBoth(a, b);
        var data = RandomBytes(8 * 1024 * 1024);
        var r = await a.SendFileAsync(new MemoryStream(data), "big.bin", data.Length, null, CancellationToken.None);
        Assert.Equal(PeerSendResult.Cancelled, r);
    }

    [Fact]
    public async Task File_NameIsSanitized_OnReceive()
    {
        var (a, b) = Pair();
        string? name = null;
        b.FileReceived = (f, temp) => { name = f.Name; return temp; };
        await StartBoth(a, b);
        var r = await a.SendFileAsync(new MemoryStream(RandomBytes(10)), "..\\..\\evil:name?.txt", 10, null, CancellationToken.None);
        Assert.Equal(PeerSendResult.Ok, r);
        Assert.NotNull(name);
        Assert.DoesNotContain("\\", name);
        Assert.DoesNotContain(":", name);
        Assert.DoesNotContain("?", name);
    }

    [Fact]
    public async Task TwoFilesInSequence_BothArrive()
    {
        var (a, b) = Pair();
        var seen = new List<string>();
        b.FileReceived = (f, temp) => { seen.Add(f.Name); return temp; };
        await StartBoth(a, b);
        Assert.Equal(PeerSendResult.Ok, await a.SendFileAsync(new MemoryStream(RandomBytes(40000)), "one.bin", 40000, null, CancellationToken.None));
        Assert.Equal(PeerSendResult.Ok, await a.SendFileAsync(new MemoryStream(RandomBytes(10)), "two.bin", 10, null, CancellationToken.None));
        Assert.Equal(new[] { "one.bin", "two.bin" }, seen);
    }

    [Fact]
    public async Task Close_RaisesClosedOnPeer()
    {
        var (a, b) = Pair();
        var closed = new TaskCompletionSource();
        b.Closed += _ => closed.TrySetResult();
        await StartBoth(a, b);
        a.Close();
        Assert.Same(closed.Task, await Task.WhenAny(closed.Task, Task.Delay(5000)));
    }
}
