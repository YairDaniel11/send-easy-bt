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

    public SettingsWindow(Settings s)
    {
        _s = s;
        // עובדים על עותק, ושומרים רק בלחיצה על "שמור"
        _favs = s.Favorites.Select(f => new FavoriteDevice { Name = f.Name, Address = f.Address, Hotkey = f.Hotkey }).ToList();
        _default = s.DefaultDevice;
        InitializeComponent();
        ModeBox.SelectedIndex = (int)s.ReceiveMode;
        DirBox.Text = s.ReceiveDir;
        (s.ChatStyle == 0 ? ChatWa : ChatGc).IsChecked = true;
        OwnBox.IsChecked = s.ExtractOwnArchives;
        AllBox.IsChecked = s.ExtractAllZips;
        DelBox.IsChecked = s.DeleteZipAfterExtract;
        HotkeyBox.IsChecked = s.HotkeyEnabled;
        HotkeyText.Text = s.Hotkey;
        DirectBox.IsChecked = s.HotkeyDirect;
        ClipBox.IsChecked = s.ClipboardHotkeyEnabled;
        ClipText.Text = s.ClipboardHotkey;
        RepoBox.Text = s.UpdateRepo;
        AttachCapture(HotkeyText);
        AttachCapture(ClipText);
        BuildFavorites();
    }

    void BuildFavorites()
    {
        FavPanel.Children.Clear();
        FavEmpty.Visibility = _favs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var f in _favs)
        {
            var g = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var def = new RadioButton { Content = "★ " + f.Name, GroupName = "default", IsChecked = f.Address == _default, VerticalAlignment = VerticalAlignment.Center,
                                        ToolTip = "מכשיר ברירת מחדל" };
            def.Checked += (_, _) => _default = f.Address;
            g.Children.Add(def);

            var hk = new TextBox { Text = f.Hotkey, IsReadOnly = true, Margin = new Thickness(8, 0, 0, 0),
                                   ToolTip = "קיצור ששולח ישירות למכשיר הזה" };
            hk.TextChanged += (_, _) => f.Hotkey = hk.Text;
            AttachCapture(hk);
            Grid.SetColumn(hk, 1); g.Children.Add(hk);

            var clear = new Button { Content = "נקה", Margin = new Thickness(6, 0, 0, 0), Background = System.Windows.Media.Brushes.Transparent, BorderThickness = new Thickness(0), ToolTip = "נקה קיצור" };
            clear.Click += (_, _) => hk.Text = "";
            Grid.SetColumn(clear, 2); g.Children.Add(clear);

            var rm = new Button { Content = "הסר", Margin = new Thickness(2, 0, 0, 0), Background = System.Windows.Media.Brushes.Transparent, BorderThickness = new Thickness(0) };
            rm.Click += (_, _) =>
            {
                _favs.Remove(f);
                if (_default == f.Address) _default = _favs.FirstOrDefault()?.Address ?? 0;
                BuildFavorites();
            };
            Grid.SetColumn(rm, 3); g.Children.Add(rm);
            FavPanel.Children.Add(g);
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
        _s.ChatStyle = ChatWa.IsChecked == true ? 0 : 1;
        _s.ExtractOwnArchives = OwnBox.IsChecked == true;
        _s.ExtractAllZips = AllBox.IsChecked == true;
        _s.DeleteZipAfterExtract = DelBox.IsChecked == true;
        _s.HotkeyEnabled = HotkeyBox.IsChecked == true;
        _s.Hotkey = HotkeyText.Text;
        _s.HotkeyDirect = DirectBox.IsChecked == true;
        _s.ClipboardHotkeyEnabled = ClipBox.IsChecked == true;
        _s.ClipboardHotkey = ClipText.Text;
        _s.UpdateRepo = RepoBox.Text.Trim();
        _s.Favorites = _favs;
        _s.DefaultDevice = _favs.Any(f => f.Address == _default) ? _default : (_favs.FirstOrDefault()?.Address ?? 0);
        DialogResult = true;
    }

    void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
