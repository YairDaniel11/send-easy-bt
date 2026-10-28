using System.Text;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace SendEasy.Core;

public sealed class ObexRejectedException(byte code) : IOException($"המכשיר דחה את הקובץ (קוד 0x{code:X2})")
{
    public byte Code { get; } = code;
    /// <summary>הדחייה נובעת מסוג הקובץ/שמו, וארגון ב-ZIP עשוי לעזור.</summary>
    public bool IsTypeRejection => Code is ObexIo.UnsupportedType or ObexIo.BadRequest;
}

/// <summary>לקוח OBEX Object Push. הכללים נלמדו בניסוי מול טלפון אנדרואיד:
/// חבילת ה-PUT הראשונה כוללת כותרות בלבד (Name, Length), בלי Type ובלי Body.</summary>
public static class ObexClient
{
    public static async Task SendFileAsync(ulong address, string path, IProgress<long>? progress, CancellationToken ct)
    {
        var dev = await BluetoothDevice.FromBluetoothAddressAsync(address)
            ?? throw new IOException("המכשיר לא נמצא");
        var svcs = await dev.GetRfcommServicesForIdAsync(RfcommServiceId.ObexObjectPush, BluetoothCacheMode.Uncached);
        var svc = svcs.Services.FirstOrDefault()
            ?? throw new IOException("המכשיר לא תומך בקבלת קבצים בבלוטוס, או שהוא כבוי/רחוק");

        using var sock = new StreamSocket();
        using var reg = ct.Register(() => sock.Dispose());
        await sock.ConnectAsync(svc.ConnectionHostName, svc.ConnectionServiceName,
            SocketProtectionLevel.BluetoothEncryptionWithAuthentication);

        var r = new DataReader(sock.InputStream);
        var w = new DataWriter(sock.OutputStream);

        await ObexIo.WriteAsync(w, 0x80, new byte[] { 0x10, 0x00, 0x20, 0x00 });
        var resp = await ObexIo.ReadPacketAsync(r);
        if (resp[0] != ObexIo.Ok) throw new ObexRejectedException(resp[0]);
        int max = Math.Min(8192, resp.Length >= 7 ? (resp[5] << 8) | resp[6] : 255);
        if (max < 255) max = 255;

        var info = new FileInfo(path);
        var first = new List<byte>();
        if (resp.Length >= 12 && resp[7] == 0xCB) first.AddRange(resp[7..12]); // ConnectionID
        var nameB = Encoding.BigEndianUnicode.GetBytes(info.Name + "\0");
        first.AddRange(new byte[] { 0x01, (byte)((nameB.Length + 3) >> 8), (byte)(nameB.Length + 3) });
        first.AddRange(nameB);
        long len = info.Length;
        first.AddRange(new byte[] { 0xC3, (byte)(len >> 24), (byte)(len >> 16), (byte)(len >> 8), (byte)len });

        await ObexIo.WriteAsync(w, 0x02, first.ToArray());
        var rp = await ObexIo.ReadPacketAsync(r);
        if (rp[0] != ObexIo.Continue && rp[0] != ObexIo.Ok) throw new ObexRejectedException(rp[0]);

        using var fs = File.OpenRead(path);
        long sent = 0;
        int room = max - 3 - 3;
        var buf = new byte[room];
        byte code = ObexIo.Continue;
        if (len == 0)
        {
            await ObexIo.WriteAsync(w, 0x82, new byte[] { 0x49, 0x00, 0x03 });
            code = (await ObexIo.ReadPacketAsync(r))[0];
        }
        while (sent < len)
        {
            ct.ThrowIfCancellationRequested();
            int n = fs.Read(buf, 0, (int)Math.Min(room, len - sent));
            if (n <= 0) throw new IOException("הקובץ השתנה בזמן השליחה");
            bool last = sent + n >= len;
            var pkt = new byte[3 + n];
            pkt[0] = (byte)(last ? 0x49 : 0x48); pkt[1] = (byte)((n + 3) >> 8); pkt[2] = (byte)(n + 3);
            Array.Copy(buf, 0, pkt, 3, n);
            await ObexIo.WriteAsync(w, (byte)(last ? 0x82 : 0x02), pkt);
            code = (await ObexIo.ReadPacketAsync(r))[0];
            sent += n;
            progress?.Report(sent);
            if (!last && code != ObexIo.Continue) break;
        }
        if (code != ObexIo.Ok) throw new ObexRejectedException(code);

        try { await ObexIo.WriteAsync(w, 0x81); await ObexIo.ReadPacketAsync(r); } catch { }
    }
}
