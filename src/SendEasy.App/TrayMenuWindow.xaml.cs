using System;
using System.Windows;
using System.Windows.Input;

namespace SendEasy.App;

/// <summary>תפריט המגש בעיצוב התוכנה (במקום תפריט Windows Forms הישן).</summary>
public partial class TrayMenuWindow : Window
{
    readonly Action _send, _chat, _history, _folder, _settings, _exit, _about;
    readonly Action<bool> _receiveChanged;
    bool _closing;

    public TrayMenuWindow(bool receiveEnabled, string status, Action send, Action chat, Action history,
        Action folder, Action settings, Action exit, Action<bool> receiveChanged, Action about)
    {
        InitializeComponent();
        _send = send; _chat = chat; _history = history; _folder = folder; _settings = settings; _exit = exit;
        _receiveChanged = receiveChanged; _about = about;
        ReceiveSwitch.IsChecked = receiveEnabled;
        StatusLine.Text = status;
        Loaded += (_, _) => PositionNearTray();
    }

    /// <summary>מציב את התפריט ליד הסמן, בתוך אזור העבודה של המסך (מעל שורת המשימות).</summary>
    void PositionNearTray()
    {
        GetCursorPos(out var p);
        var src = PresentationSource.FromVisual(this);
        double sx = src?.CompositionTarget?.TransformFromDevice.M11 ?? 1, sy = src?.CompositionTarget?.TransformFromDevice.M22 ?? 1;
        double x = p.X * sx, y = p.Y * sy;
        var area = SystemParameters.WorkArea;
        Left = Math.Min(Math.Max(area.Left, x - ActualWidth / 2), area.Right - ActualWidth);
        Top = Math.Min(y - ActualHeight, area.Bottom - ActualHeight);
        Activate();
    }

    void CloseMenu() { if (_closing) return; _closing = true; Close(); }
    void Window_Deactivated(object? s, EventArgs e) => CloseMenu();
    void Window_KeyDown(object s, KeyEventArgs e) { if (e.Key == Key.Escape) CloseMenu(); }

    void Send_Click(object s, RoutedEventArgs e) { CloseMenu(); _send(); }
    void Chat_Click(object s, RoutedEventArgs e) { CloseMenu(); _chat(); }
    void History_Click(object s, RoutedEventArgs e) { CloseMenu(); _history(); }
    void Folder_Click(object s, RoutedEventArgs e) { CloseMenu(); _folder(); }
    void Settings_Click(object s, RoutedEventArgs e) { CloseMenu(); _settings(); }
    void About_Click(object s, RoutedEventArgs e) { CloseMenu(); _about(); }
    void Exit_Click(object s, RoutedEventArgs e) { CloseMenu(); _exit(); }
    void Receive_Click(object s, RoutedEventArgs e) => _receiveChanged(ReceiveSwitch.IsChecked == true);

    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
    struct POINT { public int X, Y; }
}
