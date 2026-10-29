using System;
using System.IO;
using System.Threading.Tasks;
using SendEasy.Core;
using Windows.Media.Capture;
using Windows.Media.MediaProperties;
using Windows.Storage;

namespace SendEasy.App;

/// <summary>הקלטת הודעה קולית (AAC ב-m4a) מהמיקרופון, דרך Windows.Media.Capture. נשמרת בתיקיית הקול.</summary>
sealed class VoiceCapture
{
    MediaCapture? _cap;
    string? _path;
    DateTime _start;
    DateTime _pausedAt;
    TimeSpan _pausedTotal;
    public bool IsPaused { get; private set; }

    public bool IsRecording => _cap != null;
    /// <summary>זמן הקלטה בפועל, בלי השהיות.</summary>
    public int ElapsedSeconds => (int)Elapsed.TotalSeconds;
    TimeSpan Elapsed => (IsPaused ? _pausedAt : DateTime.Now) - _start - _pausedTotal;

    /// <summary>משהה את ההקלטה (אפשר להמשיך אחר כך לאותה הודעה).</summary>
    public async Task PauseAsync()
    {
        if (_cap == null || IsPaused) return;
        try { await _cap.PauseRecordAsync(Windows.Media.Devices.MediaCapturePauseBehavior.RetainHardwareResources); } catch { return; }
        IsPaused = true; _pausedAt = DateTime.Now;
    }

    public async Task ResumeAsync()
    {
        if (_cap == null || !IsPaused) return;
        try { await _cap.ResumeRecordAsync(); } catch { return; }
        _pausedTotal += DateTime.Now - _pausedAt;
        IsPaused = false;
    }

    /// <summary>מתחיל להקליט. בכשל (למשל אין הרשאת מיקרופון) מחזיר הודעת שגיאה, אחרת null.</summary>
    public async Task<string?> StartAsync()
    {
        try
        {
            Directory.CreateDirectory(ChatLog.VoiceDir);
            _path = Path.Combine(ChatLog.VoiceDir, "recording.tmp.m4a");
            try { File.Delete(_path); } catch { }
            var folder = await StorageFolder.GetFolderFromPathAsync(ChatLog.VoiceDir);
            var file = await folder.CreateFileAsync("recording.tmp.m4a", CreationCollisionOption.ReplaceExisting);
            _cap = new MediaCapture();
            await _cap.InitializeAsync(new MediaCaptureInitializationSettings { StreamingCaptureMode = StreamingCaptureMode.Audio });
            await _cap.StartRecordToStorageFileAsync(MediaEncodingProfile.CreateM4a(AudioEncodingQuality.Medium), file);
            _start = DateTime.Now; _pausedTotal = TimeSpan.Zero; IsPaused = false;
            return null;
        }
        catch (Exception ex)
        {
            Dispose();
            return ex is UnauthorizedAccessException || ex.HResult == unchecked((int)0x80070005)
                ? "אין גישה למיקרופון. אשר בהגדרות Windows > פרטיות > מיקרופון, כולל אפליקציות שולחן עבודה."
                : "אי אפשר להקליט: " + ex.Message;
        }
    }

    /// <summary>עוצר ושומר. מחזיר את נתיב הקובץ הסופי (voice-...-dN.m4a), או null אם קצר מדי.</summary>
    public async Task<string?> StopAsync()
    {
        var cap = _cap; var tmp = _path;
        _cap = null;
        if (cap == null || tmp == null) return null;
        if (IsPaused) { _pausedTotal += DateTime.Now - _pausedAt; IsPaused = false; }
        var elapsed = DateTime.Now - _start - _pausedTotal;
        int secs = Math.Max(1, (int)Math.Round(elapsed.TotalSeconds));
        try { await cap.StopRecordAsync(); } catch { }
        try { cap.Dispose(); } catch { }
        if (!File.Exists(tmp) || new FileInfo(tmp).Length == 0 || elapsed.TotalMilliseconds < 600)
        {
            try { File.Delete(tmp); } catch { }
            return null;
        }
        var dest = FileNames.UniquePath(ChatLog.VoiceDir, $"voice-{DateTime.Now:yyyyMMdd-HHmmss}-d{secs}.m4a");
        File.Move(tmp, dest);
        return dest;
    }

    public async Task CancelAsync()
    {
        var cap = _cap; var tmp = _path;
        _cap = null;
        if (cap != null)
        {
            try { await cap.StopRecordAsync(); } catch { }
            try { cap.Dispose(); } catch { }
        }
        if (tmp != null) try { File.Delete(tmp); } catch { }
    }

    void Dispose() { try { _cap?.Dispose(); } catch { } _cap = null; }
}