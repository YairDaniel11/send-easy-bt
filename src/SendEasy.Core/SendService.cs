namespace SendEasy.Core;

/// <summary>מתאם שליחה: מחליט מה לארוז, שולח, ואם המכשיר דוחה לפי סוג הקובץ - עוטף ב-ZIP ושולח שוב.</summary>
public static class SendService
{
    public static async Task SendAsync(ulong address, IReadOnlyList<string> paths,
        Action<string> status, IProgress<double> progress, CancellationToken ct)
    {
        var temps = new List<string>();
        try
        {
            var files = new List<string>();
            if (paths.Any(Directory.Exists))
            {
                status("אורז לארכיון ZIP...");
                var zip = ZipPacker.Pack(paths);
                temps.Add(zip);
                files.Add(zip);
            }
            else files.AddRange(paths);

            // קודם כל: האפליקציה שלנו בטלפון (כל סוג קובץ, בדיקת שלמות, ביטול). אם אין, ממשיכים ב-OBEX.
            var (peer, peerWhy) = await TryPeerAsync(address, status, ct);
            if (peer != null)
            {
                try
                {
                    await SendViaPeerAsync(peer, files, status, progress, ct);
                    status("נשלח בהצלחה");
                    return;
                }
                finally { peer.Dispose(); }
            }

            // הסיבה נשארת על המסך לאורך כל השליחה הרגילה (ובקובץ יומן), כדי שאפשר יהיה לדעת למה האפליקציה לא שימשה
            string reason = $"שליחה קלה במכשיר השני לא ענתה: {peerWhy ?? "לא ידוע"}";
            LogPeerFailure(address, reason);
            var plainStatus = status;
            status = s => plainStatus(s + "\n(" + reason + " - נשלח בבלוטוס רגיל)");
            status("שולח בבלוטוס רגיל...");
            await Task.Delay(1500, ct);
            for (int i = 0; i < files.Count; i++)
            {
                var f = files[i];
                string label = files.Count > 1 ? $"({i + 1}/{files.Count}) " : "";
                status($"{label}שולח {Path.GetFileName(f)}...");
                long size = Math.Max(new FileInfo(f).Length, 1);
                bool started = false;
                var p = new Progress<long>(b => { if (b > 0) started = true; progress.Report((double)b / size); });
                try
                {
                    await WithRetry(() => { started = false; return ObexClient.SendFileAsync(address, f, p, ct); }, status, label, () => started, ct);
                }
                catch (ObexRejectedException ex) when (ex.IsTypeRejection && !f.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                {
                    status($"{label}המכשיר לא מקבל סוג קובץ זה. עוטף ב-ZIP ושולח...");
                    var zip = ZipPacker.Pack(new[] { f });
                    temps.Add(zip);
                    size = Math.Max(new FileInfo(zip).Length, 1);
                    p = new Progress<long>(b => { if (b > 0) started = true; progress.Report((double)b / size); });
                    await WithRetry(() => { started = false; return ObexClient.SendFileAsync(address, zip, p, ct); }, status, label, () => started, ct);
                }
            }
            status("נשלח בהצלחה");
        }
        finally
        {
            foreach (var t in temps)
                try { Directory.Delete(Path.GetDirectoryName(t)!, true); } catch { }
        }
    }

    static void LogPeerFailure(ulong address, string reason)
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SendEasyBT");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "peer.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {address:X12} {reason}\n");
        }
        catch { }
    }

    /// <summary>מנסה להתחבר לאפליקציה שלנו בטלפון. null = אין אפליקציה (או שלא ניתן להתחבר), ואז משתמשים ב-OBEX.</summary>
    static async Task<(PeerSession? Session, string? Why)> TryPeerAsync(ulong address, Action<string> status, CancellationToken ct)
    {
        string? why = null;
        const int attempts = 5; // טלפון ברקע לוקח לפעמים כמה שניות להתעורר ולענות
        for (int i = 1; i <= attempts; i++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                status("בודק אם שליחה קלה מותקנת במכשיר השני (מאפשרת לשלוח כל סוג קובץ)...");
                return (await PeerClient.TryConnectAsync(address, ct), null);
            }
            catch (OperationCanceledException) { throw; }
            catch when (ct.IsCancellationRequested) { throw new OperationCanceledException(ct); }
            catch (Exception ex)
            {
                why = ErrorText.Describe(ex);
                if (i == attempts) return (null, why);
                status($"מתחבר לשליחה קלה במכשיר השני... ניסיון {i + 1} מתוך {attempts} ({ErrorText.Describe(ex)})");
                System.Diagnostics.Debug.WriteLine($"PeerClient attempt {i} failed: {ex.GetType().Name} 0x{ex.HResult:X8} {ex.Message}");
                await Task.Delay(2000, ct);
            }
        }
        return (null, why);
    }

    /// <summary>קבצים מעל הגודל הזה מנסים לעבור בוויפי (אם שני המכשירים ברשת משותפת), והשאר בבלוטוס.</summary>
    const long WifiMinBytes = 512 * 1024;

    /// <summary>העברה מעל 3MB בלי רשת משותפת: מבקשים מהטלפון נקודה חמה זמנית (המחשב מתנתק מהרשת שלו בזמן ההעברה).</summary>
    const long HotspotMinBytes = 3L * 1024 * 1024;

    /// <summary>מבקש מהטלפון קישור וויפי מוצפן ומתחבר אליו. null אם אין רשת משותפת, ואז ממשיכים בבלוטוס.</summary>
    static async Task<(PeerSession? Session, bool Hotspot)> TryWifiAsync(PeerSession bt, long totalBytes, Action<string> status, CancellationToken ct)
    {
        var s = await ConnectWifiAsync(bt, status, ct);
        if (s != null) return (s, false);
        if (totalBytes < HotspotMinBytes) return (null, false);
        status("אין רשת משותפת. מבקש מהטלפון נקודה חמה זמנית (המחשב יתנתק מהרשת שלו עד סוף ההעברה)...");
        if (!await bt.RequestHotspotAsync(TimeSpan.FromSeconds(60))) { await bt.EndHotspotAsync(); return (null, false); }
        status("מחובר לנקודה החמה של הטלפון...");
        s = await ConnectWifiAsync(bt, status, ct);
        if (s == null) { await bt.EndHotspotAsync(); return (null, false); }
        return (s, true);
    }

    static async Task<PeerSession?> ConnectWifiAsync(PeerSession bt, Action<string> status, CancellationToken ct)
    {
        try
        {
            status("בודק אם אפשר להעביר בוויפי (רשת משותפת)...");
            var offer = await bt.RequestWifiAsync(TimeSpan.FromSeconds(6));
            if (offer == null) return null;
            var link = await WifiLink.ConnectAsync(offer, ct);
            if (link == null) { Log("wifi: no route to " + string.Join(",", offer.Ips)); return null; }
            var cfg = Settings.Load();
            var s = new PeerSession(link.Value.Stream, link.Value.Stream, link.Value.Closer, cfg.GetOrCreateDeviceId(), Environment.MachineName,
                Path.Combine(Path.GetTempPath(), "SendEasyBT", "rx"), true)
            {
                OfferHandler = _ => Task.FromResult(PeerProtocol.RejectBusy),
            };
            await s.StartAsync();
            if (!await s.WaitHelloAsync(TimeSpan.FromSeconds(5))) { s.Close(); return null; }
            return s;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex) { Log("wifi: " + ex.Message); return null; }
    }

    static void Log(string m) => System.Diagnostics.Debug.WriteLine(m);

    static async Task SendViaPeerAsync(PeerSession btPeer, List<string> files, Action<string> status,
        IProgress<double> progress, CancellationToken ct)
    {
        PeerSession? wifi = null;
        bool hotspot = false;
        long total = files.Sum(f => new FileInfo(f).Length);
        if (total >= WifiMinBytes) (wifi, hotspot) = await TryWifiAsync(btPeer, total, status, ct);
        var peer = wifi ?? btPeer;
        string via = wifi != null ? (hotspot ? "בוויפי, נקודה חמה של הטלפון" : "בוויפי") : "דרך שליחה קלה במכשיר השני";
        try { await SendFilesAsync(peer, files, via, status, progress, ct); }
        finally
        {
            wifi?.Close();
            if (hotspot) await btPeer.EndHotspotAsync();
        }
    }

    static async Task SendFilesAsync(PeerSession peer, List<string> files, string via, Action<string> status,
        IProgress<double> progress, CancellationToken ct)
    {
        for (int i = 0; i < files.Count; i++)
        {
            var f = files[i];
            string label = files.Count > 1 ? $"({i + 1}/{files.Count}) " : "";
            status($"{label}שולח {Path.GetFileName(f)} ({via})...");
            long size = new FileInfo(f).Length;
            int lastPct = -1;
            var p = new Progress<long>(b =>
            {
                progress.Report(size > 0 ? (double)b / size : 1);
                int pct = size > 0 ? (int)(b * 100 / size) : 100;
                if (pct != lastPct) { lastPct = pct; status($"{label}שולח {Path.GetFileName(f)} ({via})... {pct}%"); }
            });
            await using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, true);
            var r = await peer.SendFileAsync(fs, Path.GetFileName(f), size, p, ct, isUpdate: PeerProtocol.IsUpdateApk(Path.GetFileName(f)));
            switch (r)
            {
                case PeerSendResult.Ok: break;
                case PeerSendResult.Cancelled: throw new OperationCanceledException(ct);
                case PeerSendResult.Rejected:
                    throw new IOException(peer.LastRejectReason == PeerProtocol.RejectUpToDate
                        ? "בטלפון כבר מותקנת הגרסה הזו או גרסה חדשה יותר"
                        : "המכשיר השני סירב לקבל את הקובץ");
                case PeerSendResult.HashMismatch: throw new IOException("הקובץ הגיע פגום, נסה שוב");
                default: throw new IOException("שגיאה בצד המקבל");
            }
        }
    }

    /// <summary>המכשיר רחוק, כבוי או עסוק: מנסים שוב כמה פעמים לפני שמוותרים. דחייה מפורשת של הטלפון לא נבדקת שוב.</summary>
    static async Task WithRetry(Func<Task> action, Action<string> status, string label, Func<bool> started, CancellationToken ct)
    {
        const int attempts = 3;
        for (int i = 1; ; i++)
        {
            try { await action(); return; }
            catch (Exception ex) when (ex is not OperationCanceledException and not ObexRejectedException && i < attempts && !ct.IsCancellationRequested)
            {
                // באמצע שליחה אין טעם לדבר על חיפוש/ריחוק: החיבור נקטע, ומתחילים מחדש
                status(started()
                    ? $"{label}החיבור נקטע באמצע השליחה. מנסה שוב מההתחלה ({i + 1}/{attempts})..."
                    : $"{label}לא הצלחתי להתחבר למכשיר (רחוק, כבוי או עסוק?). מנסה שוב ({i + 1}/{attempts})...");
                await Task.Delay(4000, ct);
            }
        }
    }
}
