using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;

namespace SendEasy.Core;

/// <summary>חיבור יוצא אל האפליקציה בטלפון (הפרוטוקול הפרטי). אם האפליקציה לא מותקנת או לא פתוחה: מחזיר null.</summary>
public static class PeerClient
{
    public static async Task<PeerSession?> TryConnectAsync(ulong address, CancellationToken ct)
    {
        var dev = await BluetoothDevice.FromBluetoothAddressAsync(address);
        if (dev == null) return null;
        var svcs = await dev.GetRfcommServicesForIdAsync(RfcommServiceId.FromUuid(PeerProtocol.ServiceUuid), BluetoothCacheMode.Uncached);
        var svc = svcs.Services.FirstOrDefault();
        if (svc == null) return null;

        var sock = new StreamSocket();
        using var reg = ct.Register(() => sock.Dispose());
        try
        {
            await sock.ConnectAsync(svc.ConnectionHostName, svc.ConnectionServiceName, SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
        }
        catch { sock.Dispose(); throw; }

        var cfg = Settings.Load();
        var s = new PeerSession(sock.InputStream.AsStreamForRead(), sock.OutputStream.AsStreamForWrite(), sock,
            cfg.GetOrCreateDeviceId(), Environment.MachineName, Path.Combine(Path.GetTempPath(), "SendEasyBT", "rx"), true)
        {
            OfferHandler = _ => Task.FromResult(PeerProtocol.RejectBusy), // חיבור יוצא לא מקבל קבצים
        };
        await s.StartAsync();
        if (!await s.WaitHelloAsync(TimeSpan.FromSeconds(8)))
        {
            s.Close();
            throw new IOException("האפליקציה בטלפון לא ענתה");
        }
        return s;
    }
}