using System.Windows;
using System.Windows.Input;
using Wpf.Ui.Controls;

namespace SendEasy.App;

/// <summary>חלון הודעה/אישור בעיצוב התוכנה, במקום תיבת ההודעות הישנה של המערכת.</summary>
public partial class ConfirmDialog : Window
{
    ConfirmDialog(string head, string body, string yes, string? no, SymbolRegular icon)
    {
        InitializeComponent();
        Head.Text = head;
        Body.Text = body;
        Body.Visibility = string.IsNullOrEmpty(body) ? Visibility.Collapsed : Visibility.Visible;
        Icon.Symbol = icon;
        YesBtn.Content = yes;
        if (no == null) NoBtn.Visibility = Visibility.Collapsed; else NoBtn.Content = no;
        KeyDown += (_, e) => { if (e.Key == Key.Escape) DialogResult = false; };
    }

    void Drag(object s, MouseButtonEventArgs e) { if (e.ButtonState == MouseButtonState.Pressed) DragMove(); }
    void Yes_Click(object s, RoutedEventArgs e) => DialogResult = true;

    /// <summary>חלון עם שדה טקסט. מחזיר את הטקסט שהוזן, או null אם בוטל. ריק = מחיקה.</summary>
    public static string? Prompt(Window? owner, string head, string body, string initial, string yes = "שמור")
    {
        var d = new ConfirmDialog(head, body, yes, "ביטול", SymbolRegular.Edit24);
        d.Field.Visibility = Visibility.Visible;
        d.Field.Text = initial;
        d.Loaded += (_, _) => { d.Field.Focus(); d.Field.SelectAll(); };
        d.Field.KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Enter) d.DialogResult = true; };
        if (owner is { IsLoaded: true }) d.Owner = owner; else d.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        return d.ShowDialog() == true ? d.Field.Text : null;
    }

    void No_Click(object s, RoutedEventArgs e) => DialogResult = false;

    public static bool Ask(Window? owner, string head, string body, string yes = "כן", string no = "ביטול")
    {
        var d = new ConfirmDialog(head, body, yes, no, SymbolRegular.QuestionCircle24);
        if (owner is { IsLoaded: true }) d.Owner = owner; else d.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        return d.ShowDialog() == true;
    }

    public static void Tell(Window? owner, string head, string body = "", bool warning = false)
    {
        var d = new ConfirmDialog(head, body, "אישור", null, warning ? SymbolRegular.Warning24 : SymbolRegular.Info24);
        if (owner is { IsLoaded: true }) d.Owner = owner; else d.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        d.ShowDialog();
    }
}
