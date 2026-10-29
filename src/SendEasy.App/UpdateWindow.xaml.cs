using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Windows;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>חלון עדכון: מוריד את המתקין של הגרסה החדשה, סוגר את התוכנה, ומריץ את ההתקנה (שמפעילה את התוכנה מחדש).</summary>
public partial class UpdateWindow : Wpf.Ui.Controls.FluentWindow
{
    readonly UpdateInfo _info;
    readonly Action _exitApp;
    readonly CancellationTokenSource _cts = new();
    bool _busy;

    internal UpdateWindow(UpdateInfo info, Action exitApp)
    {
        _info = info;
        _exitApp = exitApp;
        InitializeComponent();
        var cur = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
        Head.Text = "יש גרסה חדשה של שליחה קלה - BT";
        VersionText.Text = $"גרסה מותקנת: {TrayApp.Ltr(cur.ToString(3))}   ·   גרסה חדשה: {TrayApp.Ltr(info.Version.ToString(3))}"
            + (info.AssetSize > 0 ? $"   ·   {TrayApp.Ltr(TrayApp.FormatSize(info.AssetSize))}" : "");
        Notes.Text = info.Notes;
        Notes.Visibility = string.IsNullOrWhiteSpace(info.Notes) ? Visibility.Collapsed : Visibility.Visible;
        Status.Text = info.AssetUrl == null
            ? "אין קובץ התקנה בגרסה זו, אפשר להוריד מדף הגרסה."
            : "לחיצה על \"הורד והתקן\" תוריד את הגרסה, תסגור את התוכנה הנוכחית, תתקין את החדשה ותפעיל אותה מחדש.";
        if (info.AssetUrl == null) InstallBtn.Content = "פתח את דף הגרסה";
        Closed += (_, _) => _cts.Cancel();
    }

    void Later_Click(object s, RoutedEventArgs e)
    {
        if (_busy) _cts.Cancel();
        Close();
    }

    async void Install_Click(object s, RoutedEventArgs e)
    {
        if (_busy) return;
        if (_info.AssetUrl == null)
        {
            try { Process.Start(new ProcessStartInfo(_info.PageUrl) { UseShellExecute = true }); } catch { }
            return;
        }
        _busy = true;
        InstallBtn.IsEnabled = false;
        LaterBtn.Content = "ביטול";
        Bar.Visibility = Visibility.Visible;
        Status.Text = "מוריד את הגרסה החדשה...";
        try
        {
            var prog = new Progress<double>(f => Bar.Value = Math.Clamp(f * 1000, 0, 1000));
            var path = await UpdateChecker.DownloadInstallerAsync(_info, prog, _cts.Token);
            Status.Text = "מתקין... התוכנה תיסגר ותיפתח מחדש.";
            await System.Threading.Tasks.Task.Delay(600);
            // אם הפריט ברמה העליונה כבר הותקן בעבר, מתקינים אותו שוב (בלי בקשת מנהל, כי התעודה כבר מהימנה)
            var tasks = Installer.ModernMenuWasInstalled ? "/TASKS=modern" : "/TASKS=";
            Process.Start(new ProcessStartInfo(path, $"/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS {tasks}") { UseShellExecute = true });
            _exitApp();
        }
        catch (OperationCanceledException)
        {
            Status.Text = "ההורדה בוטלה.";
            Reset();
        }
        catch (Exception ex)
        {
            Status.Text = "העדכון נכשל: " + ex.Message;
            Reset();
        }
    }

    void Reset()
    {
        _busy = false;
        InstallBtn.IsEnabled = true;
        InstallBtn.Content = "נסה שוב";
        LaterBtn.Content = "אחר כך";
        Bar.Visibility = Visibility.Collapsed;
    }
}