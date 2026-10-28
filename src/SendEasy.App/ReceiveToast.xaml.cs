using System;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>חלון קטן בפינה שמראה התקדמות של קובץ נכנס, עם אפשרות ביטול.</summary>
public partial class ReceiveToast : Window
{
    readonly IncomingFile _file;
    readonly Stopwatch _clock = Stopwatch.StartNew();
    bool _finished;

    internal ReceiveToast(IncomingFile file)
    {
        _file = file;
        InitializeComponent();
        TitleText.Text = "מקבל מ-" + TrayApp.Ltr(file.FromName);
        NameText.Text = TrayApp.Ltr(file.Name);
        Loaded += (_, _) =>
        {
            var a = SystemParameters.WorkArea;
            Left = a.Right - ActualWidth - 8;
            Top = a.Bottom - ActualHeight - 8;
        };
    }

    internal void Update(long got)
    {
        if (_finished) return;
        if (_file.Size > 0) Bar.Value = Math.Min(1000, got * 1000.0 / _file.Size);
        else Bar.IsIndeterminate = true;
        double secs = Math.Max(_clock.Elapsed.TotalSeconds, 0.5);
        var speed = got / secs;
        InfoText.Text = TrayApp.Ltr(TrayApp.FormatSize(got)) + (_file.Size > 0 ? " מתוך " + TrayApp.Ltr(TrayApp.FormatSize(_file.Size)) : "")
                        + "  ·  " + TrayApp.Ltr(TrayApp.FormatSize((long)speed) + "/s");
    }

    /// <summary>הסתיים (בהצלחה או לא): מציג הודעה קצרה וסוגר.</summary>
    internal void Finish(string message)
    {
        _finished = true;
        Bar.IsIndeterminate = false;
        TitleText.Text = message;
        CancelBtn.Visibility = Visibility.Collapsed;
        InfoText.Text = "";
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.5) };
        t.Tick += (_, _) => { t.Stop(); Close(); };
        t.Start();
    }

    void Cancel_Click(object sender, RoutedEventArgs e)
    {
        _file.Cts.Cancel();
        Finish("בוטל");
    }
}
