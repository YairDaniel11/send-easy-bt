using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace SendEasy.Core;

/// <summary>גרסה חדשה שנמצאה ב-GitHub Releases: הגרסה, דף ה-Release, וקובץ המתקין (אם קיים).</summary>
public sealed record UpdateInfo(Version Version, string PageUrl, string? AssetUrl, long AssetSize, string? Sha256, string Notes);

/// <summary>בדיקת עדכון מול GitHub Releases והורדת קבצים (עם בדיקת גודל ו-SHA-256 כשהם ידועים).</summary>
public static class UpdateChecker
{
    const string InstallerName = "SendEasyBT-Setup.exe";

    static HttpClient NewClient(TimeSpan timeout)
    {
        var http = new HttpClient { Timeout = timeout };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("SendEasyBT");
        return http;
    }

    /// <summary>מחזיר את הפרטים אם יש גרסה חדשה מהנוכחית, אחרת null (גם בכל כשל רשת).</summary>
    public static async Task<UpdateInfo?> CheckDetailedAsync(string repo, Version current)
    {
        if (string.IsNullOrWhiteSpace(repo) || !repo.Contains('/')) return null;
        try
        {
            using var http = NewClient(TimeSpan.FromSeconds(10));
            var json = await http.GetStringAsync($"https://api.github.com/repos/{repo}/releases/latest");
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var tag = root.GetProperty("tag_name").GetString()?.TrimStart('v', 'V');
            var url = root.GetProperty("html_url").GetString();
            if (tag == null || url == null || !Version.TryParse(tag, out var v) || v <= current) return null;

            string? assetUrl = null, sha = null; long size = 0;
            if (root.TryGetProperty("assets", out var assets))
                foreach (var a in assets.EnumerateArray())
                {
                    if (a.GetProperty("name").GetString() != InstallerName) continue;
                    assetUrl = a.GetProperty("browser_download_url").GetString();
                    size = a.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                    sha = DigestOf(a);
                    break;
                }
            var notes = root.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "";
            return new UpdateInfo(v, url, assetUrl, size, sha, notes);
        }
        catch { return null; }
    }

    /// <summary>תאימות לאחור: גרסה וקישור לדף.</summary>
    public static async Task<(Version Version, string Url)?> CheckAsync(string repo, Version current)
    {
        var r = await CheckDetailedAsync(repo, current);
        return r == null ? null : (r.Version, r.PageUrl);
    }

    static string? DigestOf(JsonElement asset) =>
        asset.TryGetProperty("digest", out var dg) && dg.GetString() is { } d && d.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)
            ? d[7..] : null;

    static bool IsTrustedHost(Uri u) =>
        u.Scheme == Uri.UriSchemeHttps &&
        (u.Host == "github.com" || u.Host.EndsWith(".github.com") || u.Host.EndsWith(".githubusercontent.com"));

    /// <summary>מוריד את המתקין לתיקייה זמנית ומאמת. זורק IOException אם הקובץ פגום או שהכתובת לא מ-GitHub.</summary>
    public static async Task<string> DownloadInstallerAsync(UpdateInfo info, IProgress<double>? progress, CancellationToken ct)
    {
        if (info.AssetUrl == null) throw new IOException("אין קובץ התקנה בגרסה זו");
        var dir = Path.Combine(Path.GetTempPath(), "SendEasyBT", "update");
        Directory.CreateDirectory(dir);
        foreach (var old in Directory.GetFiles(dir)) try { File.Delete(old); } catch { }
        var dest = Path.Combine(dir, $"SendEasyBT-Setup-{info.Version}.exe");
        await DownloadFileAsync(info.AssetUrl, info.AssetSize, info.Sha256, dest, progress, ct);
        return dest;
    }

    /// <summary>פרטי ה-APK האחרון של אפליקציית האנדרואיד (ה-Release הגבוה ביותר שיש בו APK).</summary>
    public sealed record AndroidRelease(string Version, string FileName, string Url, long Size, string? Sha256);

    public static async Task<AndroidRelease> FindLatestAndroidAsync(string repo, CancellationToken ct)
    {
        using var http = NewClient(TimeSpan.FromSeconds(15));
        http.DefaultRequestHeaders.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoCache = true };
        var json = await http.GetStringAsync($"https://api.github.com/repos/{repo}/releases?per_page=50", ct);
        using var doc = JsonDocument.Parse(json);

        // בוחרים את הגרסה הגבוהה ביותר (לפי מספר הגרסה בתגית), לא את הראשונה ברשימה
        Version? bestVersion = null; string? bestTag = null, bestUrl = null, bestName = null, bestSha = null; long bestSize = 0;
        foreach (var rel in doc.RootElement.EnumerateArray())
        {
            var tag = rel.GetProperty("tag_name").GetString() ?? "";
            if (!rel.TryGetProperty("assets", out var assets)) continue;
            // תגית רגילה (vX.Y.Z, מתקין ו-APK באותו Release) או תגית ישנה android-vX.Y.Z
            var verText = tag.StartsWith("android-v", StringComparison.OrdinalIgnoreCase) ? tag.Substring("android-v".Length) : tag.TrimStart('v', 'V');
            // מספר הגרסה של ה-APK עצמו כתוב בהערות ה-Release (הוא לא משתנה כשמתעדכנת רק תוכנת ה-Windows)
            var bodyText = rel.TryGetProperty("body", out var bd) ? bd.GetString() ?? "" : "";
            var vm = System.Text.RegularExpressions.Regex.Match(bodyText, @"android-version:\s*([0-9.]+)");
            if (vm.Success) verText = vm.Groups[1].Value;
            if (!Version.TryParse(verText, out var ver)) continue;
            if (bestVersion != null && ver <= bestVersion) continue;
            foreach (var a in assets.EnumerateArray())
            {
                var name = a.GetProperty("name").GetString() ?? "";
                if (!name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase)) continue;
                bestVersion = ver; bestTag = tag; bestName = name;
                bestUrl = a.GetProperty("browser_download_url").GetString();
                bestSize = a.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                bestSha = DigestOf(a);
                break;
            }
        }
        if (bestUrl == null || bestName == null || bestTag == null) throw new IOException("לא נמצאה גרסת אנדרואיד ב-GitHub");
        return new AndroidRelease(bestVersion!.ToString(), bestName, bestUrl, bestSize, bestSha);
    }

    /// <summary>הנתיב של ה-APK האחרון אם הוא כבר קיים בתיקייה (בגודל הנכון), אחרת null.</summary>
    public static string? LocalAndroid(AndroidRelease r, string destDir)
    {
        var p = Path.Combine(destDir, r.FileName);
        return File.Exists(p) && (r.Size <= 0 || new FileInfo(p).Length == r.Size) ? p : null;
    }

    /// <summary>מוריד את ה-APK האחרון של אפליקציית האנדרואיד לתיקיית היעד. מחזיר נתיב וגרסה.</summary>
    public static async Task<(string Path, string Version)> DownloadLatestAndroidAsync(string repo, string destDir, IProgress<double>? progress, CancellationToken ct)
    {
        var r = await FindLatestAndroidAsync(repo, ct);
        var dest = Path.Combine(destDir, r.FileName);
        await DownloadFileAsync(r.Url, r.Size, r.Sha256, dest, progress, ct);
        return (dest, r.Version);
    }
    /// <summary>מוריד קובץ מ-GitHub אל dest (דרך קובץ זמני), עם בדיקת גודל ו-SHA-256 כשידועים.</summary>
    static async Task DownloadFileAsync(string url, long expectedSize, string? sha256, string dest, IProgress<double>? progress, CancellationToken ct)
    {
        if (!IsTrustedHost(new Uri(url))) throw new IOException("כתובת ההורדה אינה מ-GitHub");
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        var part = dest + ".part";

        using var http = NewClient(TimeSpan.FromMinutes(10));
        using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        if (resp.RequestMessage?.RequestUri is { } final && !IsTrustedHost(final)) throw new IOException("ההורדה הופנתה לכתובת לא מוכרת");
        long total = resp.Content.Headers.ContentLength ?? expectedSize;
        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
        await using (var fs = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true))
        {
            var buf = new byte[81920]; long got = 0; int n;
            while ((n = await src.ReadAsync(buf, ct)) > 0)
            {
                await fs.WriteAsync(buf.AsMemory(0, n), ct);
                got += n;
                if (total > 0) progress?.Report((double)got / total);
            }
        }
        if (expectedSize > 0 && new FileInfo(part).Length != expectedSize)
        {
            File.Delete(part);
            throw new IOException("ההורדה לא הושלמה (גודל לא תואם)");
        }
        if (!string.IsNullOrEmpty(sha256))
        {
            using var s = File.OpenRead(part);
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(s, ct));
            if (!hash.Equals(sha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(part);
                throw new IOException("הקובץ פגום (SHA-256 לא תואם)");
            }
        }
        File.Move(part, dest, true);
    }
}
