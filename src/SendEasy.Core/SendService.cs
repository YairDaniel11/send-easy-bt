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
            string reason = $"האפליקציה בטלפון לא ענתה: {peerWhy ?? "לא ידוע"}";
            LogPeerFailure(address, reason);
            var plainStatus = status;
            status = s => plainStatus(s + "\n(" + reason + " - נשלח בבלוטוס רגיל, הטלפון יבקש אישור)");
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
        for (int i = 1; i <= 3; i++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                status("בודק אם האפליקציה שלנו מותקנת בטלפון (מאפשרת לשלוח כל סוג קובץ)...");
                return (await PeerClient.TryConnectAsync(address, ct), null);
            }
            catch (OperationCanceledException) { throw; }
            catch when (ct.IsCancellationRequested) { throw new OperationCanceledException(ct); }
            catch (Exception ex)
            {
                why = ex.Message;
                if (i == 3) return (null, why);
                status($"מתחבר לאפליקציה בטלפון... ניסיון {i + 1} מתוך 3 ({ex.Message})");
                System.Diagnostics.Debug.WriteLine($"PeerClient attempt {i} failed: {ex.GetType().Name} 0x{ex.HResult:X8} {ex.Message}");
                await Task.Delay(1500, ct);
            }
        }
        return (null, why);
    }

    static async Task SendViaPeerAsync(PeerSession peer, List<string> files, Action<string> status,
        IProgress<double> progress, CancellationToken ct)
    {
        for (int i = 0; i < files.Count; i++)
        {
            var f = files[i];
            string label = files.Count > 1 ? $"({i + 1}/{files.Count}) " : "";
            status($"{label}שולח {Path.GetFileName(f)} (דרך האפליקציה)...");
            long size = new FileInfo(f).Length;
            int lastPct = -1;
            var p = new Progress<long>(b =>
            {
                progress.Report(size > 0 ? (double)b / size : 1);
                int pct = size > 0 ? (int)(b * 100 / size) : 100;
                if (pct != lastPct) { lastPct = pct; status($"{label}שולח {Path.GetFileName(f)} (דרך האפליקציה)... {pct}%"); }
            });
            await using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, true);
            var r = await peer.SendFileAsync(fs, Path.GetFileName(f), size, p, ct);
            switch (r)
            {
                case PeerSendResult.Ok: break;
                case PeerSendResult.Cancelled: throw new OperationCanceledException(ct);
                case PeerSendResult.Rejected: throw new IOException("הטלפון סירב לקבל את הקובץ");
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
