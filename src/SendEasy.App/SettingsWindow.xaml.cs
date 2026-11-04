using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using SendEasy.Core;

namespace SendEasy.App;

public partial class SettingsWindow : Wpf.Ui.Controls.FluentWindow
{
    readonly Settings _s;
    readonly List<FavoriteDevice> _favs;
    ulong _default;

    readonly Action? _openUpdate, _openAbout, _exit, _uninstall;
    readonly Action<bool>? _previewTheme;

    /// <summary>true אם המשתמש לחץ "שמור" (החלון אינו מודאלי, ולכן אין DialogResult).</summary>
    public bool Saved { get; private set; }

    public SettingsWindow(Settings s, Action? openUpdate = null, Action? openAbout = null, Action? exit = null, Action? uninstall = null, Action<bool>? previewTheme = null)
    {
        _s = s;
        _openUpdate = openUpdate; _openAbout = openAbout; _exit = exit; _uninstall = uninstall; _previewTheme = previewTheme;
        // עובדים על עותק, ושומרים רק בלחיצה על "שמור"
        _favs = s.Favorites.Select(f => new FavoriteDevice { Name = f.Name, Address = f.Address, Hotkey = f.Hotkey }).ToList();
        _default = s.DefaultDevice;
        InitializeComponent();
        Loaded += (_, _) => CheckLocalApk();
        ModeBox.SelectedIndex = (int)s.ReceiveMode;
        DirBox.Text = s.ReceiveDir;
        (s.ChatStyle == 0 ? ChatWa : s.ChatStyle == 2 ? ChatPaper : ChatGc).IsChecked = true;
        PopupBox.IsChecked = s.NotifyPopup;
        SoundBox.IsChecked = s.NotifySound;
        ToastPosBox.SelectedIndex = (int)s.ToastPosition;
        OwnBox.IsChecked = s.ExtractOwnArchives;
        AllBox.IsChecked = s.ExtractAllZips;
        DelBox.IsChecked = s.DeleteZipAfterExtract;
        HotkeyBox.IsChecked = s.HotkeyEnabled;
        HotkeyText.Text = s.Hotkey;
        DirectBox.IsChecked = s.HotkeyDirect;
        ClipBox.IsChecked = s.ClipboardHotkeyEnabled;
        ClipText.Text = s.ClipboardHotkey;
        ChatKeyBox.IsChecked = s.ChatHotkeyEnabled;
        ChatKeyText.Text = s.ChatHotkey;
        DarkBox.IsChecked = s.DarkMode;
        NavReplaceBox.IsChecked = s.NavReplacesWindow;
        VoicePreviewBox.IsChecked = s.VoicePreview;
        AttachCapture(HotkeyText);
        AttachCapture(ClipText);
        AttachCapture(ChatKeyText);
        BuildFavorites();
    }

    /// <summary>מוריד את גרסת האנדרואיד האחרונה מ-GitHub לתיקיית הקבלה ופותח את התיקייה.</summary>
    /// <summary>בודק עדכון לתוכנה: אם יש גרסה חדשה פותח את חלון העדכון (הורדה והתקנה), אחרת מציג שהגרסה עדכנית.</summary>
    async void Update_Click(object s, RoutedEventArgs e)
    {
        UpdBtn.IsEnabled = false;
        UpdStatus.Text = "בודק...";
        try
        {
            var cur = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
            var r = await UpdateChecker.CheckDetailedAsync(_s.UpdateRepo, cur);
            if (r != null && _openUpdate != null)
            {
                UpdateState.Set(r);
                UpdStatus.Text = $"יש גרסה חדשה: {TrayApp.Ltr(r.Version.ToString(3))}";
                // סוגרים את ההגדרות (בלי שמירה) ופותחים את חלון העדכון
                Close();
                Dispatcher.BeginInvoke(_openUpdate);
            }
            else UpdStatus.Text = $"הגרסה שלך ({TrayApp.Ltr(cur.ToString(3))}) עדכנית.";
        }
        catch { UpdStatus.Text = "הבדיקה נכשלה. ודא שיש חיבור לאינטרנט."; }
        UpdBtn.IsEnabled = true;
    }

    string? _apkLocal;

    /// <summary>אם ה-APK האחרון כבר נמצא בתיקיית הקבלה: מציגים שיש הגרסה העדכנית, והכפתור פותח את הקובץ במקום להוריד שוב.</summary>
    async void CheckLocalApk()
    {
        try
        {
            var dir = DirBox.Text.Trim().Length > 0 ? DirBox.Text.Trim() : _s.ReceiveDir;
            var r = await UpdateChecker.FindLatestAndroidAsync(_s.UpdateRepo, default);
            _apkLocal = UpdateChecker.LocalAndroid(r, dir);
            if (_apkLocal == null) return;
            ApkBtn.Content = "הצג את הקובץ בתיקייה";
            ApkStatus.Text = $"יש לך את גרסת האפליקציה העדכנית (גרסה {TrayApp.Ltr(r.Version)}): {TrayApp.Ltr(_apkLocal)}";
        }
        catch { }
    }

    /// <summary>שולח לטלפון את ה-APK (בלוטוס, בלי אינטרנט): קודם זה שבתיקיית התוכנה, אחר כך זה שהורדנו, ואם אין בוחרים קובץ.</summary>
    void SendApk_Click(object s, RoutedEventArgs e)
    {
        static Version? Ver(string f)
        {
            var m = System.Text.RegularExpressions.Regex.Match(System.IO.Path.GetFileName(f), @"^SendEasyBT-(\d+(?:\.\d+){0,2})\.apk$");
            return m.Success && Version.TryParse(m.Groups[1].Value, out var v) ? v : null;
        }
        string? apk = null;
        try
        {
            apk = System.IO.Directory.GetFiles(AppContext.BaseDirectory, "SendEasyBT-*.apk")
                .Select(f => (f, v: Ver(f))).Where(x => x.v != null).OrderByDescending(x => x.v).Select(x => x.f).FirstOrDefault();
        }
        catch { }
        if (apk == null && _apkLocal != null && System.IO.File.Exists(_apkLocal) && Ver(_apkLocal) != null) apk = _apkLocal;
        if (apk == null)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "בחר את קובץ האפליקציה (SendEasyBT-גרסה.apk)",
                Filter = "אפליקציית אנדרואיד|SendEasyBT-*.apk",
            };
            if (dlg.ShowDialog(this) != true) return;
            if (Ver(dlg.FileName) == null)
            {
                ApkStatus.Text = "שם הקובץ חייב להיות בצורה SendEasyBT-1.2.3.apk, כמו בדף ההורדות.";
                return;
            }
            apk = dlg.FileName;
        }
        ApkStatus.Text = "בחר את הטלפון בחלון השליחה: " + TrayApp.Ltr(System.IO.Path.GetFileName(apk));
        var w = new SendWindow(new List<string> { apk }, _s, null);
        w.Show();
        WindowFront.Bring(w);
    }

    async void Apk_Click(object s, RoutedEventArgs e)
    {
        if (_apkLocal != null && System.IO.File.Exists(_apkLocal))
        {
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{_apkLocal}\"");
            return;
        }
        ApkBtn.IsEnabled = false;
        ApkBar.Visibility = Visibility.Visible; ApkBar.Value = 0;
        ApkStatus.Text = "מוריד את האפליקציה מ-GitHub...";
        try
        {
            var prog = new Progress<double>(f => ApkBar.Value = Math.Clamp(f * 1000, 0, 1000));
            var dir = DirBox.Text.Trim().Length > 0 ? DirBox.Text.Trim() : _s.ReceiveDir;
            var (path, ver) = await UpdateChecker.DownloadLatestAndroidAsync(_s.UpdateRepo, dir, prog, default);
            _apkLocal = path;
            ApkBtn.Content = "הצג את הקובץ בתיקייה";
            ApkStatus.Text = $"ההורדה הסתיימה (גרסה {TrayApp.Ltr(ver)}): {TrayApp.Ltr(path)}\nהעבר את הקובץ לטלפון בבלוטוס או בכבל, והתקן אותו.\n(בטלפון עם חיבור לאינטרנט האפליקציה בודקת עדכונים בעצמה, ואפשר לבדוק גם במסך \"אודות\" שלה.)";
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\"");
        }
        catch (Exception ex) { ApkStatus.Text = "ההורדה נכשלה: " + ErrorText.Describe(ex); }
        finally { ApkBtn.IsEnabled = true; ApkBar.Visibility = Visibility.Collapsed; }
    }
    void BuildFavorites()
    {
        FavPanel.Children.Clear();
        FavEmpty.Visibility = _favs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var f in _favs)
        {
            var card = new Border
            {
                Margin = new Thickness(0, 0, 0, 10), Padding = new Thickness(12, 10, 12, 10), CornerRadius = new CornerRadius(8),
                Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromArgb(18, 128, 128, 128)),
            };
            var sp = new StackPanel();
            card.Child = sp;

            static TextBlock Label(string text) => new() { Text = text, FontSize = 12, Opacity = 0.7, Margin = new Thickness(0, 8, 0, 3) };

            // שם המכשיר (כינוי): כך אפשר לזהות למי שייך
            sp.Children.Add(new TextBlock { Text = "שם המכשיר (אפשר לשנות, למשל לשם הבעלים)", FontSize = 12, Opacity = 0.7, Margin = new Thickness(0, 0, 0, 3) });
            var nameBox = new TextBox { Text = f.Name, MaxLength = 60 };
            if (!System.Text.RegularExpressions.Regex.IsMatch(f.Name, "[\u0590-\u05FF]")) nameBox.FlowDirection = FlowDirection.LeftToRight;
            nameBox.TextChanged += (_, _) => f.Name = nameBox.Text.Trim();
            sp.Children.Add(nameBox);

            var def = new RadioButton
            {
                Content = "מכשיר ברירת מחדל (אליו נשלח כשלא בוחרים מכשיר)", GroupName = "default",
                IsChecked = f.Address == _default, Margin = new Thickness(0, 10, 0, 0),
            };
            def.Checked += (_, _) => _default = f.Address;
            sp.Children.Add(def);

            sp.Children.Add(Label("קיצור מקשים לשליחה ישירה למכשיר הזה"));
            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var hk = new Wpf.Ui.Controls.TextBox { Text = f.Hotkey, IsReadOnly = true, PlaceholderText = "לחץ כאן ואז על הצירוף הרצוי (למשל Ctrl+Alt+1)" };
            hk.TextChanged += (_, _) => f.Hotkey = hk.Text;
            AttachCapture(hk);
            row.Children.Add(hk);
            var clear = new Wpf.Ui.Controls.Button { Content = "נקה קיצור", Margin = new Thickness(8, 0, 0, 0) };
            clear.Click += (_, _) => hk.Text = "";
            Grid.SetColumn(clear, 1); row.Children.Add(clear);
            sp.Children.Add(row);

            var rm = new Wpf.Ui.Controls.Button { Content = "הסר מהמועדפים", Margin = new Thickness(0, 10, 0, 0), HorizontalAlignment = HorizontalAlignment.Left };
            rm.Click += (_, _) =>
            {
                _favs.Remove(f);
                if (_default == f.Address) _default = _favs.FirstOrDefault()?.Address ?? 0;
                BuildFavorites();
            };
            sp.Children.Add(rm);
            FavPanel.Children.Add(card);
        }
    }
    /// <summary>הופך תיבת טקסט ללוכדת צירוף מקשים: לוחצים בתוכה ואז על הצירוף.</summary>
    static void AttachCapture(TextBox tb)
    {
        tb.GotFocus += (_, _) => tb.SelectAll();
        tb.PreviewKeyDown += (_, e) =>
        {
            e.Handled = true;
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            if (key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt or Key.LeftShift or Key.RightShift
                or Key.LWin or Key.RWin or Key.None) return;
            if (key is Key.Escape or Key.Back or Key.Delete) { tb.Text = ""; return; }
            uint mods = 0;
            var m = Keyboard.Modifiers;
            if (m.HasFlag(ModifierKeys.Control)) mods |= HotkeySpec.Ctrl;
            if (m.HasFlag(ModifierKeys.Alt)) mods |= HotkeySpec.Alt;
            if (m.HasFlag(ModifierKeys.Shift)) mods |= HotkeySpec.Shift;
            if (m.HasFlag(ModifierKeys.Windows)) mods |= HotkeySpec.Win;
            if (mods == 0) return; // חובה מודיפייר, כדי לא לתפוס מקש רגיל בכל התוכנות
            var vk = KeyInterop.VirtualKeyFromKey(key);
            tb.Text = HotkeySpec.Format(mods, (System.Windows.Forms.Keys)vk);
        };
    }

    void Browse_Click(object sender, RoutedEventArgs e)
    {
        using var d = new System.Windows.Forms.FolderBrowserDialog { SelectedPath = DirBox.Text };
        if (d.ShowDialog() == System.Windows.Forms.DialogResult.OK) DirBox.Text = d.SelectedPath;
    }

    void Save_Click(object sender, RoutedEventArgs e)
    {
        // מזהים כפילויות בין קיצורים לפני שמירה
        var all = new List<string>();
        if (HotkeyBox.IsChecked == true) all.Add(HotkeyText.Text);
        if (ClipBox.IsChecked == true) all.Add(ClipText.Text);
        if (ChatKeyBox.IsChecked == true) all.Add(ChatKeyText.Text);
        all.AddRange(_favs.Select(f => f.Hotkey));
        var dup = all.Where(h => !string.IsNullOrWhiteSpace(h)).GroupBy(h => h, StringComparer.OrdinalIgnoreCase).FirstOrDefault(g => g.Count() > 1);
        if (dup != null)
        {
            ConfirmDialog.Tell(this, "קיצור מקשים כפול", $"הקיצור {TrayApp.Ltr(dup.Key)} מוגדר יותר מפעם אחת.", warning: true);
            return;
        }

        _s.ReceiveMode = (ReceiveMode)ModeBox.SelectedIndex;
        _s.ReceiveDir = DirBox.Text;
        _s.ChatStyle = ChatWa.IsChecked == true ? 0 : ChatPaper.IsChecked == true ? 2 : 1;
        _s.NotifyPopup = PopupBox.IsChecked == true;
        _s.NotifySound = SoundBox.IsChecked == true;
        _s.ToastPosition = (ToastPosition)ToastPosBox.SelectedIndex;
        _s.ExtractOwnArchives = OwnBox.IsChecked == true;
        _s.ExtractAllZips = AllBox.IsChecked == true;
        _s.DeleteZipAfterExtract = DelBox.IsChecked == true;
        _s.HotkeyEnabled = HotkeyBox.IsChecked == true;
        _s.Hotkey = HotkeyText.Text;
        _s.HotkeyDirect = DirectBox.IsChecked == true;
        _s.ClipboardHotkeyEnabled = ClipBox.IsChecked == true;
        _s.ClipboardHotkey = ClipText.Text;
        _s.ChatHotkeyEnabled = ChatKeyBox.IsChecked == true;
        _s.ChatHotkey = ChatKeyText.Text;
        _s.DarkMode = DarkBox.IsChecked == true;
        _s.NavReplacesWindow = NavReplaceBox.IsChecked == true;
        _s.VoicePreview = VoicePreviewBox.IsChecked == true;
        foreach (var f in _favs) if (f.Name.Length > 0) _s.SetAlias(f.Address, f.Name);
        _s.Favorites = _favs;
        _s.DefaultDevice = _favs.Any(f => f.Address == _default) ? _default : (_favs.FirstOrDefault()?.Address ?? 0);
        Saved = true;
        Close();
    }

    void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    /// <summary>מצב כהה חל מיד, גם לפני "שמור". ביטול או סגירה בלי שמירה מחזירים את המצב השמור.</summary>
    void Dark_Click(object sender, RoutedEventArgs e) => _previewTheme?.Invoke(DarkBox.IsChecked == true);

    protected override void OnClosed(EventArgs e)
    {
        if (!Saved) _previewTheme?.Invoke(_s.DarkMode);
        base.OnClosed(e);
    }

    void About_Click(object sender, RoutedEventArgs e) => _openAbout?.Invoke();

    void Exit_Click(object sender, RoutedEventArgs e) => _exit?.Invoke();

    void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        if (!ConfirmDialog.Ask(this, "להסיר את התוכנה?", "התוכנה תיסגר ותוסר מהמחשב. הקבצים שכבר התקבלו לא יימחקו.", "הסר", "ביטול", swapButtons: true)) return;
        _uninstall?.Invoke();
    }
}
