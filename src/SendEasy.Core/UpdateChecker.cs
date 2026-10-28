using System.Net.Http;
using System.Text.Json;

namespace SendEasy.Core;

/// <summary>בדיקת עדכון מול GitHub Releases. לא מוריד ולא מתקין כלום: רק מודיע שיש גרסה חדשה.</summary>
public static class UpdateChecker
{
    public static async Task<(Version Version, string Url)?> CheckAsync(string repo, Version current)
    {
        if (string.IsNullOrWhiteSpace(repo) || !repo.Contains('/')) return null;
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("SendEasyBT");
            var json = await http.GetStringAsync($"https://api.github.com/repos/{repo}/releases/latest");
            using var doc = JsonDocument.Parse(json);
            var tag = doc.RootElement.GetProperty("tag_name").GetString()?.TrimStart('v', 'V');
            var url = doc.RootElement.GetProperty("html_url").GetString();
            if (tag != null && url != null && Version.TryParse(tag, out var v) && v > current) return (v, url);
        }
        catch { }
        return null;
    }
}
