using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace SendEasy.Core;

/// <summary>גרסה חדשה שנמצאה ב-GitHub Releases: הגרסה, דף ה-Release, וקובץ המתקין (אם קיים).</summary>
public sealed record UpdateInfo(Version Version, string PageUrl, string? AssetUrl, long AssetSize, string? Sha256, string Notes);

/// <summary>בדיקת עדכון מול GitHub Releases והורדת המתקין לתיקייה זמנית (עם בדיקת גודל ו-SHA-256 כשהם ידועים).</summary>
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
                    if (a.TryGetProperty("digest", out var dg) && dg.GetString() is { } d && d.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                        sha = d[7..];
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

    static bool IsTrustedHost(Uri u) =>
        u.Scheme == Uri.UriSchemeHttps &&
        (u.Host == "github.com" || u.Host.EndsWith(".github.com") || u.Host.EndsWith(".githubusercontent.com"));

    /// <summary>מוריד את המתקין לתיקייה זמנית ומאמת. זורק IOException אם הקובץ פגום או שהכתובת לא מ-GitHub.</summary>
    public static async Task<string> DownloadInstallerAsync(UpdateInfo info, IProgress<double>? progress, CancellationToken ct)
    {
        if (info.AssetUrl == null) throw new IOException("אין קובץ התקנה בגרסה זו");
        if (!IsTrustedHost(new Uri(info.AssetUrl))) throw new IOException("כתובת ההורדה אינה מ-GitHub");
        var dir = Path.Combine(Path.GetTempPath(), "SendEasyBT", "update");
        Directory.CreateDirectory(dir);
        foreach (var old in Directory.GetFiles(dir)) try { File.Delete(old); } catch { }
        var dest = Path.Combine(dir, $"SendEasyBT-Setup-{info.Version}.exe");
        var part = dest + ".part";

        using var http = NewClient(TimeSpan.FromMinutes(10));
        using var resp = await http.GetAsync(info.AssetUrl, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        if (resp.RequestMessage?.RequestUri is { } final && !IsTrustedHost(final)) throw new IOException("ההורדה הופנתה לכתובת לא מוכרת");
        long total = resp.Content.Headers.ContentLength ?? info.AssetSize;
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
        if (info.AssetSize > 0 && new FileInfo(part).Length != info.AssetSize)
        {
            File.Delete(part);
            throw new IOException("ההורדה לא הושלמה (גודל לא תואם)");
        }
        if (!string.IsNullOrEmpty(info.Sha256))
        {
            using var s = File.OpenRead(part);
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(s, ct));
            if (!hash.Equals(info.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(part);
                throw new IOException("קובץ ההתקנה פגום (SHA-256 לא תואם)");
            }
        }
        File.Move(part, dest, true);
        return dest;
    }
}