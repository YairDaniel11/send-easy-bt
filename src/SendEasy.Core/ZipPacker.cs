using System.IO.Compression;
using System.Text.Json;

namespace SendEasy.Core;

/// <summary>אריזת קבצים ותיקיות ל-ZIP עם סימון מזהה, כך שמקבל שמריץ את התוכנה יחלץ אותו.</summary>
public static class ZipPacker
{
    public const string MarkerEntry = "__sendeasy.json";

    /// <summary>אורז את הפריטים (קבצים/תיקיות) לקובץ ZIP אחד בתיקיית temp. מחזיר את הנתיב.</summary>
    public static string Pack(IReadOnlyList<string> paths, string? archiveName = null)
    {
        string baseName = archiveName ?? (paths.Count == 1
            ? Path.GetFileName(paths[0].TrimEnd('\\', '/'))
            : "קבצים");
        if (baseName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) baseName = baseName[..^4];
        baseName = FileNames.Sanitize(baseName);

        var dir = Path.Combine(Path.GetTempPath(), "SendEasyBT", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        var zipPath = Path.Combine(dir, baseName + ".zip");

        using (var fs = File.Create(zipPath))
        using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
        {
            // שורש הארכיון: אם פריט בודד הוא תיקייה, תוכנה נכנס ישירות (המקבל יוצר תיקייה בשם הסימון)
            bool single = paths.Count == 1;
            foreach (var p in paths)
            {
                if (Directory.Exists(p))
                {
                    var root = Path.GetFullPath(p).TrimEnd('\\', '/');
                    string prefix = single ? "" : Path.GetFileName(root) + "/";
                    AddEmptyDirsAndFiles(zip, root, prefix);
                }
                else if (File.Exists(p))
                {
                    zip.CreateEntryFromFile(p, Path.GetFileName(p), CompressionLevel.Optimal);
                }
            }
            var marker = zip.CreateEntry(MarkerEntry);
            using var w = marker.Open();
            JsonSerializer.Serialize(w, new { app = "SendEasyBT", version = 1, name = baseName });
        }
        return zipPath;
    }

    static void AddEmptyDirsAndFiles(ZipArchive zip, string root, string prefix)
    {
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            var rel = Path.GetRelativePath(root, file).Replace('\\', '/');
            zip.CreateEntryFromFile(file, prefix + rel, CompressionLevel.Optimal);
        }
        foreach (var d in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories))
        {
            if (Directory.EnumerateFileSystemEntries(d).Any()) continue;
            var rel = Path.GetRelativePath(root, d).Replace('\\', '/');
            zip.CreateEntry(prefix + rel + "/");
        }
    }
}
