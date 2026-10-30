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

    readonly Action? _openUpdate;

    public SettingsWindow(Settings s, Action? openUpdate = null)
    {
        _s = s;
        _openUpdate = openUpdate;
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
        OwnBox.IsChecked = s.ExtractOwnArchives;
        AllBox.IsChecked = s.ExtractAllZips;
        DelBox.IsChecked = s.DeleteZipAfterExtract;
        HotkeyBox.IsChecked = s.HotkeyEnabled;
        HotkeyText.Text = s.Hotkey;
        DirectBox.IsChecked = s.HotkeyDirect;
        ClipBox.IsChecked = s.ClipboardHotkeyEnabled;
        ClipText.Text = s.ClipboardHotkey;
        AttachCapture(HotkeyText);
        AttachCapture(ClipText);
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
                // ההגדרות הן חלון מודאלי ויחסמו את חלון העדכון, לכן סוגרים אותן (בלי שמירה) ופותחים את העדכון
                DialogResult = false;
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
            ApkStatus.Text = $"הורדה הסתיימה (גרסה {TrayApp.Ltr(ver)}): {TrayApp.Ltr(path)}\nהעבר את האפליקצייה לטלפון באמצעות הבלוטוס או באמצעות כבל, והתקן אותו במכשיר.\n(במכשיר עם גישה לאינטרנט - תוכל לעדכן את האפליקצייה מתוך הגדרות האפליקצייה)";
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\"");
        }
        catch (Exception ex) { ApkStatus.Text = "ההורדה נכשלה: " + ex.Message; }
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
        _s.ExtractOwnArchives = OwnBox.IsChecked == true;
        _s.ExtractAllZips = AllBox.IsChecked == true;
        _s.DeleteZipAfterExtract = DelBox.IsChecked == true;
        _s.HotkeyEnabled = HotkeyBox.IsChecked == true;
        _s.Hotkey = HotkeyText.Text;
        _s.HotkeyDirect = DirectBox.IsChecked == true;
        _s.ClipboardHotkeyEnabled = ClipBox.IsChecked == true;
        _s.ClipboardHotkey = ClipText.Text;
        foreach (var f in _favs) if (f.Name.Length > 0) _s.SetAlias(f.Address, f.Name);
        _s.Favorites = _favs;
        _s.DefaultDevice = _favs.Any(f => f.Address == _default) ? _default : (_favs.FirstOrDefault()?.Address ?? 0);
        DialogResult = true;
    }

    void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
