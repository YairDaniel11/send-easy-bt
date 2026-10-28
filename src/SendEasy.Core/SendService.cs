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

            for (int i = 0; i < files.Count; i++)
            {
                var f = files[i];
                string label = files.Count > 1 ? $"({i + 1}/{files.Count}) " : "";
                status($"{label}שולח {Path.GetFileName(f)}...");
                long size = Math.Max(new FileInfo(f).Length, 1);
                var p = new Progress<long>(b => progress.Report((double)b / size));
                try
                {
                    await WithRetry(() => ObexClient.SendFileAsync(address, f, p, ct), status, label, ct);
                }
                catch (ObexRejectedException ex) when (ex.IsTypeRejection && !f.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                {
                    status($"{label}המכשיר לא מקבל סוג קובץ זה. עוטף ב-ZIP ושולח...");
                    var zip = ZipPacker.Pack(new[] { f });
                    temps.Add(zip);
                    size = Math.Max(new FileInfo(zip).Length, 1);
                    p = new Progress<long>(b => progress.Report((double)b / size));
                    await WithRetry(() => ObexClient.SendFileAsync(address, zip, p, ct), status, label, ct);
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

    /// <summary>המכשיר רחוק, כבוי או עסוק: מנסים שוב כמה פעמים לפני שמוותרים. דחייה מפורשת של הטלפון לא נבדקת שוב.</summary>
    static async Task WithRetry(Func<Task> action, Action<string> status, string label, CancellationToken ct)
    {
        const int attempts = 3;
        for (int i = 1; ; i++)
        {
            try { await action(); return; }
            catch (Exception ex) when (ex is not OperationCanceledException and not ObexRejectedException && i < attempts && !ct.IsCancellationRequested)
            {
                status($"{label}המכשיר לא זמין (רחוק או כבוי?). מנסה שוב ({i + 1}/{attempts})...");
                await Task.Delay(4000, ct);
            }
        }
    }
}
