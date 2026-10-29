using System;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Navigation;

namespace SendEasy.App;

/// <summary>אודות: גרסה, תיאור קצר וקישור למאגר ב-GitHub.</summary>
public partial class AboutWindow : Wpf.Ui.Controls.FluentWindow
{
    readonly Action _openUpdate;

    public AboutWindow(Action openUpdate)
    {
        _openUpdate = openUpdate;
        InitializeComponent();
        var v = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
        VersionText.Text = "גרסה " + TrayApp.Ltr(v.ToString(3));
        try
        {
            // האייקון בגודל הגדול ביותר שיש בקובץ (חד), ולא האייקון הקטן של הקובץ המריץ
            var dec = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri("pack://application:,,,/Assets/app.ico"),
                System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad);
            Logo.Source = dec.Frames.OrderByDescending(f => f.PixelWidth).First();
        }
        catch { }
    }

    void Repo_Navigate(object s, RequestNavigateEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true }); } catch { }
        e.Handled = true;
    }

    /// <summary>בודק עדכון עכשיו: אם יש גרסה חדשה פותח את חלון העדכון (הורדה והתקנה), אחרת מציג שהגרסה עדכנית.</summary>
    async void Update_Click(object s, RoutedEventArgs e)
    {
        UpdBtn.IsEnabled = false;
        UpdBtn.Content = "בודק...";
        try
        {
            var cur = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
            var r = await SendEasy.Core.UpdateChecker.CheckDetailedAsync(SendEasy.Core.Settings.Load().UpdateRepo, cur);
            if (r != null)
            {
                UpdateState.Set(r);
                _openUpdate();
                Close();
                return;
            }
            UpdBtn.Content = "הגרסה שלך עדכנית";
        }
        catch { UpdBtn.Content = "הבדיקה נכשלה"; }
        await System.Threading.Tasks.Task.Delay(2500);
        UpdBtn.Content = "בדוק עדכון";
        UpdBtn.IsEnabled = true;
    }

    void Close_Click(object s, RoutedEventArgs e) => Close();
}