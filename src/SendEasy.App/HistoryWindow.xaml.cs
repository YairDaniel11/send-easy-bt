using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using SendEasy.Core;

namespace SendEasy.App;

sealed class HistoryRow
{
    public required HistoryEntry E { get; init; }
    public string Name => E.Name;
    public string Arrow => !E.Ok ? "⚠" : E.Sent ? "↑" : "↓";
    public Brush ArrowBrush => !E.Ok ? Brushes.OrangeRed : E.Sent ? Brushes.SteelBlue : Brushes.SeaGreen;
    public string Detail =>
        (E.Sent ? "נשלח אל " : "התקבל מ-") + TrayApp.Ltr(E.Device) + (E.Size > 0 ? "  ·  " + TrayApp.Ltr(TrayApp.FormatSize(E.Size)) : "")
        + (E.Ok ? "" : "  ·  נכשל" + (string.IsNullOrEmpty(E.Note) ? "" : ": " + E.Note));
    public string When => E.Time.Date == DateTime.Today ? E.Time.ToString("HH:mm") : E.Time.ToString("dd/MM HH:mm");
}

public partial class HistoryWindow : Wpf.Ui.Controls.FluentWindow
{
    public HistoryWindow()
    {
        InitializeComponent();
        Reload();
    }

    void Reload()
    {
        var rows = History.Load().Select(e => new HistoryRow { E = e }).ToList();
        List.ItemsSource = rows;
        EmptyText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    HistoryEntry? Current => (List.SelectedItem as HistoryRow)?.E;

    static bool Exists(string? p) => !string.IsNullOrEmpty(p) && (File.Exists(p) || Directory.Exists(p));

    void OpenItem()
    {
        var e = Current;
        if (e == null) return;
        if (!Exists(e.Path)) { ConfirmDialog.Tell(this, "הקובץ לא נמצא", "הקובץ כבר לא נמצא במקום שבו נשמר.", warning: true); return; }
        try { Process.Start(new ProcessStartInfo(e.Path!) { UseShellExecute = true }); } catch { }
    }

    void List_SelectionChanged(object s, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        var c = Current;
        bool ok = c != null && Exists(c.Path);
        OpenBtn.IsEnabled = ShowBtn.IsEnabled = ok;
        Hint.Text = c == null ? "בחר פריט מהרשימה"
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

    void Clear_Click(object s, RoutedEventArgs e)
    {
        if (ConfirmDialog.Ask(this, "למחוק את כל ההיסטוריה?", "הקבצים עצמם לא יימחקו.", "מחק", "ביטול")) { History.Clear(); Reload(); }
    }
}
