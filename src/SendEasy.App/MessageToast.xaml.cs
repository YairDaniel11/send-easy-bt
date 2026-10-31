using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>חלון קטן ושקט בפינה על הודעה חדשה. לחיצה פותחת את הצ'אט; נעלם לבד אחרי כמה שניות.</summary>
public partial class MessageToast : Window
{
    readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(7) };

    internal MessageToast(string title, string body, Action onClick, ToastPosition pos)
    {
        InitializeComponent();
        TitleText.Text = title;
        Emoticons.Fill(BodyText, Emoticons.ToCodes(body), 14);
        MouseLeftButtonUp += (_, e) => { onClick(); Close(); };
        MouseRightButtonUp += (_, _) => Close();
        _timer.Tick += (_, _) => { _timer.Stop(); Close(); };
        Loaded += (_, _) =>
        {
            TrayApp.PlaceToast(this, pos);
            _timer.Start();
        };
    }
}