using System.IO.Compression;
using System.Text.Json;

namespace SendEasy.Core;

public sealed class UnsafeArchiveException(string message) : Exception(message);

/// <summary>חילוץ ZIP בטוח: מונע zip-slip, שמות שמורים, פצצות ZIP, ודורס כלום.</summary>
public static class SafeExtractor
{
    /// <summary>האם הארכיון נוצר על ידי התוכנה (מכיל את קובץ הסימון).</summary>
    public static bool IsOwnArchive(string zipPath, out string? name)
    {
        name = null;
        try
        {
            using var zip = ZipFile.OpenRead(zipPath);
            var m = zip.GetEntry(ZipPacker.MarkerEntry);
            if (m == null) return false;
            using var s = m.Open();
            using var doc = JsonDocument.Parse(s);
            if (doc.RootElement.TryGetProperty("app", out var app) && app.GetString() == "SendEasyBT")
            {
                if (doc.RootElement.TryGetProperty("name", out var n)) name = n.GetString();
                return true;
            }
        }
        catch { }
        return false;
    }

    /// <summary>מחלץ לתיקייה חדשה בתוך destParent. מחזיר את נתיב התיקייה. בכל חשד: מוחק ומשליך UnsafeArchiveException.</summary>
    public static string Extract(string zipPath, string destParent, long maxBytes, int maxFiles, string? preferredName = null)
    {
        Directory.CreateDirectory(destParent);
        using var zip = ZipFile.OpenRead(zipPath);

        string folderName = FileNames.Sanitize(preferredName ?? Path.GetFileNameWithoutExtension(zipPath));
        string dest = FileNames.UniquePath(destParent, folderName);
        Directory.CreateDirectory(dest);
        string destRoot = Path.GetFullPath(dest).TrimEnd('\\') + "\\";

        try
        {
            if (zip.Entries.Count > maxFiles) throw new UnsafeArchiveException("יותר מדי קבצים בארכיון");
            long declared = 0;
            foreach (var e in zip.Entries) declared += e.Length;
            if (declared > maxBytes) throw new UnsafeArchiveException("הארכיון גדול מדי לאחר חילוץ");
            var packedSize = new FileInfo(zipPath).Length;
            if (packedSize > 0 && declared / Math.Max(packedSize, 1) > 1000 && declared > 100L * 1024 * 1024)
                throw new UnsafeArchiveException("יחס דחיסה חשוד");

            long written = 0;
            foreach (var entry in zip.Entries)
            {
                if (entry.FullName == ZipPacker.MarkerEntry) continue;
                var target = ResolveTarget(entry.FullName, destRoot);

                if (entry.FullName.EndsWith('/') || entry.FullName.EndsWith('\\'))
                {
                    Directory.CreateDirectory(target);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                using var src = entry.Open();
                using var dst = new FileStream(target, FileMode.CreateNew, FileAccess.Write);
                var buf = new byte[81920];
                int n;
                while ((n = src.Read(buf, 0, buf.Length)) > 0)
                {
                    written += n;
                    if (written > maxBytes) throw new UnsafeArchiveException("הארכיון גדול מדי לאחר חילוץ");
                    dst.Write(buf, 0, n);
                }
            }
            return dest;
        }
        catch
        {
            try { Directory.Delete(dest, true); } catch { }
            throw;
        }
    }

    /// <summary>ממיר שם רשומה בארכיון לנתיב מלא בתוך destRoot, או זורק אם הוא חשוד.</summary>
    public static string ResolveTarget(string entryName, string destRoot)
    {
        if (entryName.Length == 0) throw new UnsafeArchiveException("שם רשומה ריק");
        if (entryName.Contains(':')) throw new UnsafeArchiveException($"נתיב חשוד: {entryName}");
        if (entryName[0] == '/' || entryName[0] == '\\') throw new UnsafeArchiveException($"נתיב מוחלט: {entryName}");

        var parts = entryName.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        foreach (var seg in parts)
            if (FileNames.IsReservedOrInvalidSegment(seg))
                throw new UnsafeArchiveException($"שם לא חוקי בארכיון: {entryName}");

        var full = Path.GetFullPath(Path.Combine(destRoot, string.Join('\\', parts)));
        if (!full.StartsWith(destRoot, StringComparison.OrdinalIgnoreCase))
            throw new UnsafeArchiveException($"נתיב יוצא מהתיקייה: {entryName}");
        return full;
    }
}
