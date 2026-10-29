using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>תהליך הרקע: אייקון במגש, שרת קבלה, קיצורי מקשים וקבלת בקשות שליחה מהקליק הימני.</summary>
sealed class TrayApp : IDisposable
{
    const int HotkeyMain = 1, HotkeyClipboard = 2, HotkeyFavoriteBase = 100;

    readonly NotifyIcon _tray;
    readonly System.Windows.Threading.Dispatcher _ui = System.Windows.Application.Current.Dispatcher;
    readonly ObexServer _server;
    readonly PeerServer _peer;
    readonly HotkeyManager _hotkey = new();
    readonly Dictionary<IncomingFile, ReceiveToast> _toasts = new();
    readonly Settings _settings = Settings.Load();
    string? _lastReceivedPath;
    string? _updateUrl;

    public TrayApp()
    {
        _tray = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "שליחה קלה - BT",
            Visible = true,
        };
        _tray.BalloonTipClicked += OnBalloonClicked;
        _tray.DoubleClick += (_, _) => OpenSend(Array.Empty<string>());
        _tray.MouseUp += (_, e) => { if (e.Button == MouseButtons.Right) ShowMenu(); };

        _server = new ObexServer(() => _settings) { Decide = AskAsync };
        _server.Received += OnReceived;
        _server.Progress += (f, got) => _ui.BeginInvoke(() => OnProgress(f, got));
        _server.Aborted += f => _ui.BeginInvoke(() => OnAborted(f));
        _server.Error += msg => Debug.WriteLine("receive error: " + msg);
        // הפרוטוקול הפרטי: מול האפליקציה שלנו בטלפון (כל סוג קובץ, בדיקת שלמות, הודעות)
        _peer = new PeerServer(() => _settings) { Decide = AskAsync };
        _peer.Received += OnReceived;
        _peer.Progress += (f, got) => _ui.BeginInvoke(() => OnProgress(f, got));
        _peer.Aborted += f => _ui.BeginInvoke(() => OnAborted(f));
        _peer.TextReceivedEx += (from, addr, text, id) => _ui.BeginInvoke(() => OnTextReceived(from, addr, text, id));
        PeerConnections.ReadReceived += (addr, id) => _ui.BeginInvoke(() =>
        {
            if (ChatLog.MarkRead(addr, id) && _chat is { IsLoaded: true }) _chat.Refresh(addr, "");
        });
        _peer.ReceivedFrom += (addr, f, path) => _ui.BeginInvoke(() => OnFileInChat(addr, f, path));
        PeerConnections.NewChatReceived += addr => _ui.BeginInvoke(() =>
        {
            if (ChatLog.NewThread(addr) && _chat is { IsLoaded: true }) _chat.Refresh(addr, "");
        });
        _peer.Error += msg => Debug.WriteLine("peer error: " + msg);
        _ = StartServerAsync();

        _hotkey.Pressed += OnHotkey;
        ApplyHotkeys();
        _ = CheckUpdateAsync();
        // בדיקת עדכון אוטומטית כל 6 שעות כשהתוכנה פתוחה (ההתראה מוצגת פעם אחת לכל גרסה)
        var updTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromHours(6) };
        updTimer.Tick += (_, _) => _ = CheckUpdateAsync();
        updTimer.Start();
    }

    /// <summary>אייקון התוכנה (מוטמע בקובץ ההרצה). אם נכשל: אייקון מערכת.</summary>
    static Icon LoadIcon()
    {
        try { return Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application; }
        catch { return SystemIcons.Application; }
    }

    // ---- תפריט ----

    TrayMenuWindow? _menu;
    ChatWindow? _chat;
    HistoryWindow? _history;

    void ShowMenu()
    {
        _ui.BeginInvoke(() =>
        {
            _menu?.Close();
            _menu = new TrayMenuWindow(_settings.ReceiveEnabled,
                _settings.ReceiveEnabled ? "שליחת וקבלת קבצים בבלוטוס" : "קבלת הקבצים כבויה",
                send: () => OpenSend(Array.Empty<string>()),
                chat: OpenChat,
                history: OpenHistory,
                folder: () => OpenFolder(_settings.ReceiveDir),
                settings: ShowSettings,
                exit: ExitApp,
                receiveChanged: on => { _settings.ReceiveEnabled = on; _settings.Save(); },
                about: ShowAbout);
            _menu.Show();
        });
    }

    public void OpenChat() => OpenChat(0, "");

    /// <summary>פותח את חלון הצ'אט, ואם ניתנה כתובת: את השיחה עם אותו מכשיר.</summary>
    public void OpenChat(ulong address, string name)
    {
        if (_chat is not { IsLoaded: true })
        {
            _chat = new ChatWindow(_settings);
            _chat.Show();
        }
        if (address != 0) _chat.Open(address, name);
        WindowFront.Bring(_chat);
    }

    AboutWindow? _about;

    public void ShowAbout()
    {
        if (_about is { IsLoaded: true }) { WindowFront.Bring(_about); return; }
        _about = new AboutWindow();
        _about.Show();
        WindowFront.Bring(_about);
    }

    public void OpenHistory()
    {
        if (_history is { IsLoaded: true }) { WindowFront.Bring(_history); return; }
        _history = new HistoryWindow();
        _history.Show();
        WindowFront.Bring(_history);
    }

    public void ShowSettings()
    {
        var w = new SettingsWindow(_settings);
        w.Loaded += (_, _) => WindowFront.Bring(w);
        if (w.ShowDialog() == true) { _settings.Save(); ApplyHotkeys(); _chat?.ReloadStyle(); }
    }

    // ---- קיצורי מקשים ----

    void ApplyHotkeys()
    {
        _hotkey.UnregisterAll();
        var failed = new List<string>();
        void Reg(int id, string spec) { if (!string.IsNullOrWhiteSpace(spec) && !_hotkey.Register(id, spec)) failed.Add(spec); }

        if (_settings.HotkeyEnabled) Reg(HotkeyMain, _settings.Hotkey);
        if (_settings.ClipboardHotkeyEnabled) Reg(HotkeyClipboard, _settings.ClipboardHotkey);
        for (int i = 0; i < _settings.Favorites.Count; i++) Reg(HotkeyFavoriteBase + i, _settings.Favorites[i].Hotkey);

        if (failed.Count > 0)
            _tray.ShowBalloonTip(6000, "שליחה קלה - BT",
                "קיצורי מקשים תפוסים או לא תקינים: " + string.Join(", ", failed) + ". אפשר לשנות אותם בהגדרות.", ToolTipIcon.Warning);
    }

    FavoriteDevice? DefaultDevice =>
        _settings.DefaultDevice == 0 ? null : _settings.Favorites.FirstOrDefault(f => f.Address == _settings.DefaultDevice);

    void OnHotkey(int id)
    {
        if (id == HotkeyClipboard) { SendClipboardText(); return; }

        FavoriteDevice? target = null;
        if (id >= HotkeyFavoriteBase && id - HotkeyFavoriteBase < _settings.Favorites.Count)
            target = _settings.Favorites[id - HotkeyFavoriteBase];
        else if (id == HotkeyMain && _settings.HotkeyDirect) target = DefaultDevice;

        var paths = ExplorerSelection.GetSelectedPaths();
        if (paths.Count == 0)
        {
            _tray.ShowBalloonTip(3000, "שליחה קלה - BT", "לא נבחרו קבצים. בחר קבצים בסייר ולחץ שוב על הקיצור.", ToolTipIcon.Info);
            return;
        }
        OpenSend(paths, target);
    }

    /// <summary>שולח את הטקסט או הקישור שבלוח כקובץ טקסט קטן למכשיר ברירת המחדל (או לפי בחירה).</summary>
    void SendClipboardText()
    {
        string? text = null;
        try { if (System.Windows.Clipboard.ContainsText()) text = System.Windows.Clipboard.GetText(); } catch { }
        if (string.IsNullOrWhiteSpace(text))
        {
            _tray.ShowBalloonTip(3000, "שליחה קלה - BT", "אין טקסט בלוח לשליחה.", ToolTipIcon.Info);
            return;
        }
        var dir = Path.Combine(Path.GetTempPath(), "SendEasyBT", "clip");
        Directory.CreateDirectory(dir);
        bool isLink = Uri.TryCreate(text.Trim(), UriKind.Absolute, out var u) && (u.Scheme is "http" or "https");
        var file = Path.Combine(dir, (isLink ? "קישור-" : "טקסט-") + DateTime.Now.ToString("HHmmss") + ".txt");
        File.WriteAllText(file, text, new System.Text.UTF8Encoding(true));
        OpenSend(new[] { file }, DefaultDevice);
    }

    // ---- קבלה ----

    async Task StartServerAsync()
    {
        try { await _server.StartAsync(); }
        catch (Exception ex)
        {
            _tray.ShowBalloonTip(6000, "שליחה קלה - BT",
                "לא ניתן להפעיל קבלה בבלוטוס: " + ex.Message, ToolTipIcon.Warning);
        }
        try { await _peer.StartAsync(); }
        catch (Exception ex) { Debug.WriteLine("peer start failed: " + ex.Message); }
    }

    ulong _chatBalloonAddr;
    string _chatBalloonName = "";

    void OnTextReceived(string from, ulong addr, string text, long msgId)
    {
        ChatLog.Add(addr, new ChatEntry { Mine = false, Kind = "text", Text = text, Device = from, Status = "delivered", MsgId = msgId });
        if (_chat is { IsLoaded: true }) _chat.Refresh(addr, from);
        if (_chat is { IsActive: true }) return; // החלון פתוח ובחזית: ההודעה כבר מוצגת
        var shown = Emoticons.ToGlyphs(text.Length > 200 ? text[..200] + "…" : text);
        NotifyMessage(addr, from, shown);
    }

    MessageToast? _msgToast;

    /// <summary>הודעה חדשה: חלון קטן שקט (אם מופעל) וצליל (אם מופעל). לחיצה על החלון פותחת את השיחה.</summary>
    void NotifyMessage(ulong addr, string from, string preview)
    {
        if (_settings.NotifySound) System.Media.SystemSounds.Asterisk.Play();
        if (!_settings.NotifyPopup) return;
        try { _msgToast?.Close(); } catch { }
        _msgToast = new MessageToast("הודעה מ-" + from, preview, () => OpenChat(addr, from));
        _msgToast.Show();
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
    static extern bool CreateHardLink(string newFile, string existingFile, IntPtr reserved);

    void OnFileInChat(ulong addr, IncomingFile f, string path)
    {
        bool voice = ChatLog.IsVoiceName(f.Name);
        // תמונה בצ'אט: עותק משלנו, כדי שהיא תוצג גם אם הקובץ בתיקיית הקבלה יימחק או יזוז
        if (!voice && System.IO.Path.GetExtension(f.Name).ToLowerInvariant() is ".jpg" or ".jpeg" or ".png" or ".gif" or ".bmp" or ".webp")
        {
            try
            {
                System.IO.Directory.CreateDirectory(ChatLog.ImageDir);
                var copy = FileNames.UniquePath(ChatLog.ImageDir, f.Name);
                // קישור קשיח (אותו קובץ בדיסק, בלי מקום נוסף ובלי תלות בשם המקורי); אם לא אפשרי (דיסק אחר) - העתקה
                if (!CreateHardLink(copy, path, IntPtr.Zero)) System.IO.File.Copy(path, copy);
                path = copy;
            }
            catch { }
        }
        ChatLog.Add(addr, new ChatEntry { Mine = false, Kind = voice ? "voice" : "file", Text = f.Name, Size = f.Size, Path = path, Device = f.FromName, Status = "delivered" });
        if (_chat is { IsLoaded: true }) _chat.Refresh(addr, f.FromName);
        if (voice && _chat is not { IsActive: true }) NotifyMessage(addr, f.FromName, "🎤 הודעה קולית");
        else if (!voice && f.FromChat && _chat is not { IsActive: true }) NotifyMessage(addr, f.FromName, "📎 " + f.Name);
    }
    Task<bool> AskAsync(IncomingFile f)
    {
        var tcs = new TaskCompletionSource<bool>();
        _ui.BeginInvoke(async () =>
        {
            if (_settings.ReceiveMode == ReceiveMode.AutoFromAll
                || (_settings.ReceiveMode == ReceiveMode.AutoFromPaired && f.FromPaired))
            { tcs.SetResult(true); return; }
            var size = f.Size >= 0 ? $" ({FormatSize(f.Size)})" : "";
            var box = new Wpf.Ui.Controls.MessageBox
            {
                Title = "שליחה קלה - BT",
                FlowDirection = System.Windows.FlowDirection.RightToLeft,
                Content = $"\"{f.FromName}\" רוצה לשלוח לך:\n{f.Name}{size}\n\nלקבל?",
                PrimaryButtonText = "קבל",
                CloseButtonText = "דחה",
            };
            var res = await box.ShowDialogAsync();
            tcs.SetResult(res == Wpf.Ui.Controls.MessageBoxResult.Primary);
        });
        return tcs.Task;
    }

    void OnProgress(IncomingFile f, long got)
    {
        // הקלטות ותמונות מהצ'אט מופיעות בשיחה ולא בחלון קבלה
        if (f.FromChat || ChatLog.IsVoiceName(f.Name)) return;
        if (!_toasts.TryGetValue(f, out var toast))
        {
            toast = new ReceiveToast(f);
            _toasts[f] = toast;
            toast.Closed += (_, _) => _toasts.Remove(f);
            toast.Show();
        }
        toast.Update(got);
        _tray.Text = Trim($"מקבל {f.Name}");
    }

    void OnAborted(IncomingFile f)
    {
        var why = f.Cts.IsCancellationRequested ? "בוטל" : "החיבור נותק";
        if (_toasts.TryGetValue(f, out var t)) t.Finish(why);
        History.Add(new HistoryEntry { Sent = false, Device = f.FromName, Name = f.Name, Size = f.Size, Ok = false, Note = why });
        _tray.Text = "שליחה קלה - BT";
    }

    void OnReceived(IncomingFile f, string path)
    {
        _ui.BeginInvoke(() =>
        {
            _tray.Text = "שליחה קלה - BT";
            string shown = path;
            string note = "";
            try
            {
                if (path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                {
                    bool own = SafeExtractor.IsOwnArchive(path, out var name);
                    if ((own && _settings.ExtractOwnArchives) || _settings.ExtractAllZips)
                    {
                        shown = SafeExtractor.Extract(path, _settings.ReceiveDir,
                            _settings.MaxExtractBytes, _settings.MaxExtractFiles, own ? name : null);
                        note = "חולץ";
                        if (_settings.DeleteZipAfterExtract) File.Delete(path);
                    }
                }
            }
            catch (UnsafeArchiveException ex) { note = "לא חולץ: " + ex.Message; }
            catch (Exception ex) { note = "שגיאת חילוץ: " + ex.Message; }

            _lastReceivedPath = shown;
            _chatBalloonAddr = 0;
            if (_toasts.TryGetValue(f, out var t)) t.Finish("התקבל: " + Path.GetFileName(shown));
            else if (!f.FromChat) _tray.ShowBalloonTip(5000, "התקבל מ-" + f.FromName, Path.GetFileName(shown), ToolTipIcon.Info); // תמונה מהצ'אט: ההודעה מוצגת רק כהודעת צ'אט
            History.Add(new HistoryEntry { Sent = false, Device = f.FromName, Name = Path.GetFileName(shown), Size = f.Size, Path = shown, Note = note });
        });
    }

    void OnBalloonClicked(object? s, EventArgs e)
    {
        if (_updateUrl != null) { _updateUrl = null; OpenUpdate(); return; }
        if (_chatBalloonAddr != 0) { var a = _chatBalloonAddr; _chatBalloonAddr = 0; OpenChat(a, _chatBalloonName); return; }
        if (_lastReceivedPath == null) return;
        Process.Start("explorer.exe", $"/select,\"{_lastReceivedPath}\"");
    }

    static void OpenFolder(string dir)
    {
        Directory.CreateDirectory(dir);
        Process.Start("explorer.exe", $"\"{dir}\"");
    }

    // ---- שליחה ----

    readonly List<string> _pending = new();
    FavoriteDevice? _pendingTarget;
    System.Windows.Threading.DispatcherTimer? _batchTimer;

    /// <summary>פותח חלון שליחה. כשבוחרים כמה קבצים בסייר, כל אחד מגיע בנפרד, ולכן אוספים אותם לחלון אחד.</summary>
    public void OpenSend(IReadOnlyList<string> paths, FavoriteDevice? target = null)
    {
        if (!_ui.CheckAccess()) { _ui.BeginInvoke(() => OpenSend(paths, target)); return; }
        _pending.AddRange(paths);
        _pendingTarget = target ?? _pendingTarget;
        _batchTimer?.Stop();
        _batchTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
        _batchTimer.Tick += (_, _) =>
        {
            _batchTimer!.Stop();
            var items = _pending.Distinct().ToList();
            var tgt = _pendingTarget;
            _pending.Clear(); _pendingTarget = null;
            var w = new SendWindow(items, _settings, tgt);
            w.Show();
            WindowFront.Bring(w);
        };
        _batchTimer.Start();
    }

    public void ListenForPaths(string pipeName)
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    using var s = new NamedPipeServerStream(pipeName, PipeDirection.In);
                    await s.WaitForConnectionAsync();
                    using var r = new StreamReader(s);
                    var list = new List<string>();
                    string? line;
                    while ((line = await r.ReadLineAsync()) != null)
                        if (File.Exists(line) || Directory.Exists(line)) list.Add(line);
                    OpenSend(list);
                }
                catch { await Task.Delay(500); }
            }
        });
    }

    // ---- עדכונים ----

    Version? _notifiedVersion;

    async Task CheckUpdateAsync()
    {
        var cur = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
        var r = await UpdateChecker.CheckDetailedAsync(_settings.UpdateRepo, cur);
        if (r == null) return;
        _ui.BeginInvoke(() =>
        {
            UpdateState.Set(r);
            if (r.Version == _notifiedVersion) return; // התראה פעם אחת לכל גרסה, אבל הבאנר בחלונות נשאר
            _notifiedVersion = r.Version;
            _updateUrl = r.PageUrl;
            _tray.ShowBalloonTip(8000, "יש גרסה חדשה של שליחה קלה - BT",
                $"גרסה {r.Version.ToString(3)}. לחץ כאן כדי להוריד ולהתקין.", ToolTipIcon.Info);
        });
    }

    /// <summary>פותח את חלון העדכון (הורדה והתקנה בתוך התוכנה).</summary>
    public void OpenUpdate()
    {
        if (!_ui.CheckAccess()) { _ui.BeginInvoke(OpenUpdate); return; }
        var info = UpdateState.Available;
        if (info == null) return;
        if (_updateWindow is { IsLoaded: true }) { WindowFront.Bring(_updateWindow); return; }
        _updateWindow = new UpdateWindow(info, ExitApp);
        _updateWindow.Show();
        WindowFront.Bring(_updateWindow);
    }

    UpdateWindow? _updateWindow;
    void ExitApp()
    {
        _tray.Visible = false;
        _server.Dispose();
        _peer.Dispose();
        System.Windows.Application.Current.Shutdown();
    }

    static string Trim(string s) => s.Length > 60 ? s[..60] : s; // מגבלת טקסט של NotifyIcon

    /// <summary>עוטף טקסט לועזי/מספרים כך שיוצג משמאל לימין בתוך משפט בעברית (למשל "46.1 MB").</summary>
    internal static string Ltr(string s) => "⁦" + s + "⁩";

    internal static string FormatSize(long b) =>
        b >= 1 << 30 ? $"{b / (double)(1 << 30):0.0} GB" :
        b >= 1 << 20 ? $"{b / (double)(1 << 20):0.0} MB" :
        b >= 1 << 10 ? $"{b / 1024.0:0} KB" : $"{b} B";

    public void Dispose() { _hotkey.Dispose(); _tray.Dispose(); _server.Dispose(); _peer.Dispose(); PeerConnections.CloseAll(); }
}
