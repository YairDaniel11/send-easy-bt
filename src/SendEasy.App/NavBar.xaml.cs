using System.Windows;
using System.Windows.Controls;

namespace SendEasy.App;

/// <summary>שורת כפתורים למעבר בין חלונות התוכנה (שליחה, צ'אט, קבצים, הגדרות). Current = החלון שבו היא מוצגת, והוא מוסתר.</summary>
public partial class NavBar : UserControl
{
    public static readonly DependencyProperty CurrentProperty = DependencyProperty.Register(
        nameof(Current), typeof(string), typeof(NavBar), new PropertyMetadata("", (d, _) => ((NavBar)d).Apply()));

    public string Current { get => (string)GetValue(CurrentProperty); set => SetValue(CurrentProperty, value); }

    public NavBar()
    {
        InitializeComponent();
        Apply();
    }

    void Apply()
    {
        if (SendBtn == null) return;
        SendBtn.Visibility = Current == "send" ? Visibility.Collapsed : Visibility.Visible;
        ChatBtn.Visibility = Current == "chat" ? Visibility.Collapsed : Visibility.Visible;
        HistoryBtn.Visibility = Current == "history" ? Visibility.Collapsed : Visibility.Visible;
        SettingsBtn.Visibility = Current == "settings" ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>אחרי המעבר לחלון אחר: סוגר את החלון הנוכחי (לפי ההגדרה). חלון השליחה לא נסגר, כי ייתכן שיש בו שליחה בתהליך.</summary>
    void CloseHost()
    {
        if (Current == "send" || TrayApp.Instance?.NavReplacesWindow != true) return;
        var host = Window.GetWindow(this);
        if (host == null) return;
        host.Dispatcher.BeginInvoke(new System.Action(() => { try { host.Close(); } catch { } }));
    }

    void Send_Click(object s, RoutedEventArgs e) { TrayApp.Instance?.OpenSendWindow(); CloseHost(); }
    void Chat_Click(object s, RoutedEventArgs e) { TrayApp.Instance?.OpenChat(); CloseHost(); }
    void History_Click(object s, RoutedEventArgs e) { TrayApp.Instance?.OpenHistory(); CloseHost(); }
    void Settings_Click(object s, RoutedEventArgs e) { TrayApp.Instance?.ShowSettings(); CloseHost(); }
}
