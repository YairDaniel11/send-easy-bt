using System.IO.Compression;
using SendEasy.Core;
using Xunit;

public class ArchiveTests : IDisposable
{
    readonly string _tmp = Path.Combine(Path.GetTempPath(), "sebt-test-" + Guid.NewGuid().ToString("N"));
    public ArchiveTests() => Directory.CreateDirectory(_tmp);
    public void Dispose() { try { Directory.Delete(_tmp, true); } catch { } }

    string MakeZip(params (string name, string content)[] entries)
    {
        var path = Path.Combine(_tmp, Guid.NewGuid().ToString("N") + ".zip");
        using var fs = File.Create(path);
        using var zip = new ZipArchive(fs, ZipArchiveMode.Create);
        foreach (var (n, c) in entries)
        {
            var e = zip.CreateEntry(n);
            using var w = new StreamWriter(e.Open()); w.Write(c);
        }
        return path;
    }

    [Theory]
    [InlineData("../evil.txt")]
    [InlineData("a/../../evil.txt")]
    [InlineData(@"..\evil.txt")]
    [InlineData("/abs.txt")]
    [InlineData(@"C:\abs.txt")]
    [InlineData("a/file.txt:stream")]
    [InlineData("CON")]
    [InlineData("dir/NUL.txt")]
    [InlineData("trailing./x.txt")]
    public void Rejects_unsafe_entries_and_leaves_nothing(string name)
    {
        var zip = MakeZip(("ok.txt", "x"), (name, "evil"));
        var dest = Path.Combine(_tmp, "out");
        Assert.Throws<UnsafeArchiveException>(() => SafeExtractor.Extract(zip, dest, 1 << 20, 100));
        Assert.Empty(Directory.EnumerateFileSystemEntries(dest));
        Assert.False(File.Exists(Path.Combine(_tmp, "evil.txt")));
    }

    [Fact]
    public void Extracts_normal_archive_into_new_folder_without_overwriting()
    {
        var zip = MakeZip(("a.txt", "A"), ("sub/b.txt", "B"));
        var dest = Path.Combine(_tmp, "out");
        var d1 = SafeExtractor.Extract(zip, dest, 1 << 20, 100, "תיקייה");
        var d2 = SafeExtractor.Extract(zip, dest, 1 << 20, 100, "תיקייה");
        Assert.NotEqual(d1, d2);
        Assert.Equal("B", File.ReadAllText(Path.Combine(d1, "sub", "b.txt")));
    }

    [Fact]
    public void Enforces_size_and_count_limits()
    {
        var zip = MakeZip(("a.txt", new string('x', 5000)));
        Assert.Throws<UnsafeArchiveException>(() => SafeExtractor.Extract(zip, Path.Combine(_tmp, "o1"), 1000, 100));
        var zip2 = MakeZip(("1.txt", "1"), ("2.txt", "2"));
        Assert.Throws<UnsafeArchiveException>(() => SafeExtractor.Extract(zip2, Path.Combine(_tmp, "o2"), 1 << 20, 1));
    }

    [Fact]
    public void Folder_roundtrip_through_marker_restores_folder_name()
    {
        var src = Path.Combine(_tmp, "תמונות");
        Directory.CreateDirectory(Path.Combine(src, "sub"));
        Directory.CreateDirectory(Path.Combine(src, "empty"));
        File.WriteAllText(Path.Combine(src, "a.txt"), "A");
        File.WriteAllText(Path.Combine(src, "sub", "b.txt"), "B");

        var zip = ZipPacker.Pack(new[] { src });
        try
        {
            Assert.True(SafeExtractor.IsOwnArchive(zip, out var name));
            Assert.Equal("תמונות", name);
            var outDir = SafeExtractor.Extract(zip, Path.Combine(_tmp, "recv"), 1 << 20, 100, name);
            Assert.Equal("תמונות", Path.GetFileName(outDir));
            Assert.Equal("B", File.ReadAllText(Path.Combine(outDir, "sub", "b.txt")));
            Assert.True(Directory.Exists(Path.Combine(outDir, "empty")));
        }
        finally { Directory.Delete(Path.GetDirectoryName(zip)!, true); }
    }

    [Fact]
    public void Foreign_zip_is_not_own_archive()
    {
        Assert.False(SafeExtractor.IsOwnArchive(MakeZip(("a.txt", "x")), out _));
    }

    [Theory]
    [InlineData("CON", "_CON")]
    [InlineData(@"a/b\c.txt", "c.txt")]
    [InlineData("name. ", "name")]
    public void Sanitize_names(string raw, string expected) => Assert.Equal(expected, FileNames.Sanitize(raw));
}
