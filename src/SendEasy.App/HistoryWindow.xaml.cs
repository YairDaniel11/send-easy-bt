using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using SendEasy.Core;

namespace SendEasy.App;

enum FileKind { Image, Document, Audio, Video, Other }

sealed class HistoryRow
{
    public required HistoryEntry E { get; init; }
    public string Name => E.Name;
    public string Arrow => !E.Ok ? "⚠ נכשל" : E.Sent ? "↑ נשלח אל" : "↓ התקבל מ-";
    public Brush ArrowBrush => !E.Ok ? Brushes.OrangeRed : E.Sent ? Brushes.SteelBlue : Brushes.SeaGreen;
    public string Detail =>
        TrayApp.Ltr(E.Device) + (E.Size > 0 ? "  ·  " + TrayApp.Ltr(TrayApp.FormatSize(E.Size)) : "")
        + (E.Ok || string.IsNullOrEmpty(E.Note) ? "" : "  ·  " + E.Note);
    public string When => E.Time.Date == DateTime.Today ? E.Time.ToString("HH:mm")
        : E.Time.Date == DateTime.Today.AddDays(-1) ? "אתמול " + E.Time.ToString("HH:mm")
        : E.Time.ToString("dd/MM HH:mm");
    public string Group => (DateTime.Today - E.Time.Date).Days switch { <= 0 => "היום", 1 => "אתמול", < 7 => "השבוע", _ => "מוקדם יותר" };

    public FileKind Kind => KindOf(E.Name);
    public string Ext => Path.GetExtension(E.Name).TrimStart('.').ToUpperInvariant() is { Length: > 0 and <= 4 } x ? x : "FILE";
    public Brush IconBrush => Kind switch
    {
        FileKind.Image => new SolidColorBrush(Color.FromRgb(0x3f, 0x8f, 0x6b)),
        FileKind.Document => new SolidColorBrush(Color.FromRgb(0xc2, 0x5a, 0x3a)),
        FileKind.Audio => new SolidColorBrush(Color.FromRgb(0x8a, 0x5f, 0xb8)),
        FileKind.Video => new SolidColorBrush(Color.FromRgb(0xc0, 0x40, 0x7a)),
        _ => new SolidColorBrush(Color.FromRgb(0x5f, 0x6f, 0x82)),
    };

    static readonly HashSet<string> Images = new() { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic" };
    static readonly HashSet<string> Docs = new() { ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".rtf", ".odt" };
    static readonly HashSet<string> Audios = new() { ".mp3", ".m4a", ".wav", ".ogg", ".aac", ".amr", ".opus" };
    static readonly HashSet<string> Videos = new() { ".mp4", ".mkv", ".avi", ".mov", ".3gp", ".webm" };

    public static FileKind KindOf(string name)
    {
        var x = Path.GetExtension(name).ToLowerInvariant();
        return Images.Contains(x) ? FileKind.Image : Docs.Contains(x) ? FileKind.Document
             : Audios.Contains(x) ? FileKind.Audio : Videos.Contains(x) ? FileKind.Video : FileKind.Other;
    }
}

sealed record DeviceRow(string? Key, string Name, int Count);

/// <summary>חלון הקבצים: כל מה שנשלח ומה שהתקבל, עם סינון לפי מכשיר, כיוון, סוג ושם.</summary>
public partial class HistoryWindow : Wpf.Ui.Controls.FluentWindow
{
    List<HistoryRow> _all = new();
    string? _device;               // null = כל המכשירים
    string _dir = "all";           // all / recv / sent / fail
    FileKind? _kind;               // null = כל הסוגים
    bool _loading;

    public HistoryWindow()
    {
        InitializeComponent();
        BuildChips();
        Reload();
    }

    void BuildChips()
    {
        foreach (var (key, text) in new[] { ("all", "הכל"), ("recv", "התקבלו"), ("sent", "נשלחו"), ("fail", "נכשלו") })
            DirChips.Children.Add(Chip("dir", text, key == "all", () => { _dir = key; Refresh(); }));
        TypeChips.Children.Add(Chip("kind", "כל הסוגים", true, () => { _kind = null; Refresh(); }));
        foreach (var (k, text) in new[] { (FileKind.Image, "תמונות"), (FileKind.Document, "מסמכים"), (FileKind.Audio, "שמע"), (FileKind.Video, "וידאו"), (FileKind.Other, "אחר") })
            TypeChips.Children.Add(Chip("kind", text, false, () => { _kind = k; Refresh(); }));
    }

    static RadioButton Chip(string group, string text, bool on, Action onPick)
    {
        var b = new RadioButton
        {
            GroupName = group, Content = text, IsChecked = on, Margin = new Thickness(0, 0, 6, 0), Padding = new Thickness(12, 4, 12, 4),
        };
        // מראה של כפתור מתג (במקום עיגול רדיו); אם אין סגנון כזה, נשאר המראה הרגיל
        if (Application.Current.TryFindResource(typeof(System.Windows.Controls.Primitives.ToggleButton)) is Style st) b.Style = st;
        b.Checked += (_, _) => onPick();
        return b;
    }

    void Reload()
    {
        _all = History.Load().Select(e => new HistoryRow { E = e }).ToList();
        Refresh();
    }

    bool Match(HistoryRow r, bool ignoreDevice = false)
    {
        if (!ignoreDevice && _device != null && r.E.Device != _device) return false;
        if (_dir == "recv" && (r.E.Sent || !r.E.Ok)) return false;
        if (_dir == "sent" && (!r.E.Sent || !r.E.Ok)) return false;
        if (_dir == "fail" && r.E.Ok) return false;
        if (_kind != null && r.Kind != _kind) return false;
        var q = SearchBox.Text.Trim();
        return q.Length == 0 || r.E.Name.Contains(q, StringComparison.CurrentCultureIgnoreCase);
    }

    void Refresh()
    {
        if (!IsInitialized || DevList == null || _loading) return;
        _loading = true;
        try
        {
            var devices = new List<DeviceRow> { new(null, "כל המכשירים", _all.Count(r => Match(r, true))) };
            devices.AddRange(_all.Where(r => r.E.Device.Length > 0).GroupBy(r => r.E.Device)
                .OrderByDescending(g => g.Max(r => r.E.Time))
                .Select(g => new DeviceRow(g.Key, g.Key, g.Count(r => Match(r, true)))));
            DevList.ItemsSource = devices;
            DevList.SelectedItem = devices.FirstOrDefault(d => d.Key == _device) ?? devices[0];
            if (DevList.SelectedItem is DeviceRow sel && sel.Key != _device) _device = sel.Key;

            var rows = _all.Where(r => Match(r)).ToList();
            var view = new ListCollectionView(rows);
            view.GroupDescriptions.Add(new PropertyGroupDescription(nameof(HistoryRow.Group)));
            List.ItemsSource = view;
            Heading.Text = _device ?? "כל המכשירים";
            CountText.Text = rows.Count + " קבצים";
            EmptyText.Text = _all.Count == 0 ? "עדיין אין קבצים" : "אין קבצים שמתאימים לסינון";
            EmptyText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        finally { _loading = false; }
        List_SelectionChanged(this, null!);
    }

    HistoryEntry? Current => (List.SelectedItem as HistoryRow)?.E;

    static bool Exists(string? p) => !string.IsNullOrEmpty(p) && (File.Exists(p) || Directory.Exists(p));

    void DevList_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        if (_loading || DevList.SelectedItem is not DeviceRow d) return;
        _device = d.Key;
        Refresh();
    }

    void Search_TextChanged(object s, TextChangedEventArgs e) => Refresh();

    void OpenItem()
    {
        var e = Current;
        if (e == null) return;
        if (!Exists(e.Path)) { ConfirmDialog.Tell(this, "הקובץ לא נמצא", "הקובץ כבר לא נמצא במקום שבו נשמר.", warning: true); return; }
        try { Process.Start(new ProcessStartInfo(e.Path!) { UseShellExecute = true }); } catch { }
    }

    void List_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        var c = Current;
        bool ok = c != null && Exists(c.Path);
        OpenBtn.IsEnabled = ShowBtn.IsEnabled = ok;
        RemoveBtn.IsEnabled = c != null;
        Hint.Text = c == null ? "בחר קובץ מהרשימה"
            : !c.Ok ? "השליחה נכשלה, אין קובץ לפתוח"
            : string.IsNullOrEmpty(c.Path) ? "לפריט זה לא נשמר מיקום"
            : !ok ? "הקובץ כבר לא נמצא במקום שבו נשמר" : "";
    }

    void Open_Click(object s, RoutedEventArgs e) => OpenItem();
    void List_DoubleClick(object s, MouseButtonEventArgs e) => OpenItem();

    void Show_Click(object s, RoutedEventArgs e)
    {
        var c = Current;
        if (c == null || !Exists(c.Path)) return;
        Process.Start("explorer.exe", $"/select,\"{c.Path}\"");
    }

    void Remove_Click(object s, RoutedEventArgs e)
    {
        var c = Current;
        if (c == null) return;
        History.Remove(c);
        Reload();
    }

    void Clear_Click(object s, RoutedEventArgs e)
    {
        if (ConfirmDialog.Ask(this, "למחוק את כל הרשימה?", "הקבצים עצמם לא יימחקו.", "מחק", "ביטול")) { History.Clear(); Reload(); }
    }
}
