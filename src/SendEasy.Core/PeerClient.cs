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
        if (dev == null) throw new IOException("Windows לא מצא את המכשיר");
        var id = RfcommServiceId.FromUuid(PeerProtocol.ServiceUuid);
        // טלפון שהאפליקציה בו רק ברקע (אין חיבור פעיל אליו): החיפוש החי נכשל לפעמים, ולכן מנסים גם מהמטמון של Windows
        var live = await dev.GetRfcommServicesForIdAsync(id, BluetoothCacheMode.Uncached);
        var svc = live.Services.FirstOrDefault();
        var cachedRes = svc == null ? await dev.GetRfcommServicesForIdAsync(id, BluetoothCacheMode.Cached) : null;
        svc ??= cachedRes!.Services.FirstOrDefault();

        // חיפוש השירות נכשל (קורה בטלפון ישן או כשאין חיבור פעיל): מתחברים ישירות לפי כתובת ומזהה השירות
        var host = svc?.ConnectionHostName
            ?? new Windows.Networking.HostName("(" + string.Join(":", Enumerable.Range(0, 6).Select(i => ((address >> (40 - 8 * i)) & 0xFF).ToString("X2"))) + ")");
        var service = svc?.ConnectionServiceName ?? "{" + PeerProtocol.ServiceUuid.ToString("D") + "}";
        // לאבחון: איך מצאנו את השירות (חיפוש חי, מטמון או חיבור ישיר)
        var how = live.Services.Count > 0 ? "חיפוש חי" : svc != null ? "מטמון של Windows" : "חיבור ישיר";

        var sock = new StreamSocket();
        using var reg = ct.Register(() => sock.Dispose());
        try
        {
            await sock.ConnectAsync(host, service, SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
        }
        catch (Exception ex) when (svc == null)
        {
            sock.Dispose();
            throw new IOException($"שליחה קלה לא נמצאה במכשיר השני (חיפוש: {live.Error}, חיבור ישיר: 0x{ex.HResult:X8})");
        }
        catch { sock.Dispose(); throw; }

        var cfg = Settings.Load();
        var s = new PeerSession(sock.InputStream.AsStreamForRead(), sock.OutputStream.AsStreamForWrite(), sock,
            cfg.GetOrCreateDeviceId(), Environment.MachineName, Path.Combine(Path.GetTempPath(), "SendEasyBT", "rx"), true)
        {
            OfferHandler = _ => Task.FromResult(PeerProtocol.RejectBusy), // חיבור יוצא לא מקבל קבצים
        };
        await s.StartAsync();
        // טלפון שהמסך שלו כבוי לפעמים מתעורר לאט, ולכן ממתינים עד 15 שניות
        if (!await s.WaitHelloAsync(TimeSpan.FromSeconds(15)))
        {
            s.Close();
            throw new IOException($"החיבור לטלפון נוצר ({how}) אבל האפליקציה בו לא ענתה תוך 15 שניות. סגור ופתח אותה בטלפון, ובדוק שהקבלה ברקע פעילה");
        }
        return s;
    }
}